# Reranker 接入与 Agent 终答修复 Task

> 基于已批准的 plan.md，按依赖顺序拆解为可独立验证的子任务。

## 任务拆解原则

1. **依赖优先**：底层模块先行（RerankClient → HybridRetriever → AgentLoop）
2. **测试驱动**：每个任务包含对应单元测试，可独立验证
3. **配置后置**：application.yaml 与环境变量在基础设施完成后统一补齐
4. **集成评测**：r08/r07b 评测作为最终验收

---

## Task 1: 新增 RerankResult 与 Rerank 配置子类

**目标：** 定义 rerank 数据结构与配置模型，为后续实现奠定基础。

**产出文件：**
- `src/main/java/com/agenticrag/rag/retrieve/RerankResult.java`（新增）
- `src/main/java/com/agenticrag/config/RagProperties.java`（修改）

**详细步骤：**

### 1.1 新增 RerankResult record
```java
package com.agenticrag.rag.retrieve;

/**
 * Rerank 单个结果：原始候选索引 + 相关性分数（0-1，由 API 按降序返回）。
 */
public record RerankResult(int index, double relevanceScore) {}
```

### 1.2 修改 RagProperties.Retrieval，嵌套 Rerank 子类
在 `RagProperties.Retrieval` 静态类内新增：
```java
@Data
public static class Rerank {
    /** Rerank 总开关（默认关闭） */
    private boolean enabled = false;
    
    /** Rerank 模型名（硅基流动支持的模型，默认 bge-reranker-v2-m3） */
    private String model = "BAAI/bge-reranker-v2-m3";
    
    /** 送入 rerank 的候选文档数量（RRF 后取 top N 进行精排） */
    private int candidateSize = 20;
    
    /** 精排后保留数量（null 时回退为 retrieval.finalTopN） */
    private Integer finalTopN;
}
```

在 `Retrieval` 类内新增字段：
```java
private Rerank rerank = new Rerank();
```

**验收：**
- [ ] `RerankResult` 编译通过，record 签名正确
- [ ] `RagProperties` 编译通过，IDEA 无红线
- [ ] 运行 `./mvnw compile` 成功

---

## Task 2: 实现 RerankClient 与单元测试

**目标：** 完成硅基流动 `/v1/rerank` 客户端，复用 `LlmProperties` 配置，fail-open 设计。

**产出文件：**
- `src/main/java/com/agenticrag/rag/retrieve/RerankClient.java`（新增）
- `src/test/java/com/agenticrag/rag/retrieve/RerankClientTest.java`（新增）

**详细步骤：**

### 2.1 实现 RerankClient 核心逻辑

参照 `EmbeddingClient.java` 的模式：
- 依赖 `LlmProperties`（通过构造函数注入）
- DCL 懒加载 `HttpClient`（与 EmbeddingClient 同模式，connectTimeout 15s）
- 对外接口：
  ```java
  public Optional<List<RerankResult>> rerank(String query, List<String> documents,
                                             int topN, String model)
  ```

**HTTP 请求细节：**
- URL: `baseUrl + "/rerank"`（trimTrailingSlash 处理）
- Method: POST
- Headers: `Authorization: Bearer {apiKey}`, `Content-Type: application/json`
- Body:
  ```json
  {
    "model": "BAAI/bge-reranker-v2-m3",
    "query": "用户查询文本",
    "documents": ["文档1", "文档2", ...],
    "top_n": 5,
    "return_documents": false
  }
  ```
- Timeout: `properties.getTimeoutSeconds()`（默认 120s）

**响应解析：**
```json
{
  "results": [
    {"index": 2, "relevance_score": 0.89},
    {"index": 0, "relevance_score": 0.76},
    ...
  ]
}
```
解析为 `List<RerankResult>`。

**异常处理（fail-open）：**
- 网络异常（IOException / TimeoutException）：重试 1 次（间隔 1s），仍失败返回 `Optional.empty()`，记 WARN 日志
- 4xx/5xx：不重试，直接返回 `Optional.empty()`，记 WARN 日志（包含 status code 与 body 前 200 字符）
- JSON 解析失败：返回 `Optional.empty()`，记 WARN 日志
- **所有失败路径不抛异常**，由调用方检测 empty 决定降级策略

**日志规范：**
- INFO：成功时记录"Rerank 完成：{docCount} 文档 → top {topN}，耗时 {ms}ms"
- WARN：失败时记录"Rerank 调用失败：{原因简述}，回退原序"（不输出完整 query，截断到 50 字符）

### 2.2 编写 RerankClientTest 单元测试

使用 Mockito mock `LlmProperties`，测试三路径：

**Test 1: 成功路径**
- Mock `baseUrl` 返回假 URL（不真实请求，用 WireMock 或手工 mock）
- 构造成功响应 JSON（3 个结果，index 乱序）
- 断言返回 `Optional.isPresent()`，结果列表长度=3，index 顺序符合响应

**Test 2: 网络异常路径**
- Mock HTTP 调用抛 `IOException`
- 断言返回 `Optional.isEmpty()`
- 验证日志包含 "Rerank 调用失败"

**Test 3: 4xx 响应路径**
- Mock HTTP 返回 status 400 + body `{"error":"invalid model"}`
- 断言返回 `Optional.isEmpty()`
- 验证日志包含 "400" 与 error 信息

**验收：**
- [ ] `./mvnw test -Dtest=RerankClientTest` 全绿
- [ ] 代码覆盖率 >80%（成功/失败/重试三路径）

---

## Task 3: HybridRetriever 接入 Rerank 分支

**目标：** 在 RRF 融合之后、构造 `RetrievedChunk` 列表之前插入 rerank 精排逻辑。

**产出文件：**
- `src/main/java/com/agenticrag/rag/retrieve/HybridRetriever.java`（修改）
- `src/test/java/com/agenticrag/rag/retrieve/HybridRetrieverTest.java`（修改/新增）

**详细步骤：**

### 3.1 HybridRetriever 构造函数改动

新增依赖：
```java
private final RerankClient rerankClient;  // 可选注入
```

构造函数：
```java
public HybridRetriever(EmbeddingClient embeddingClient,
                       VectorStore vectorStore,
                       Bm25Store bm25Store,
                       RagProperties ragProperties,
                       QueryRewriter queryRewriter,
                       HydeExpander hydeExpander,
                       @Autowired(required = false) RerankClient rerankClient) {
    // ... 现有赋值
    this.rerankClient = rerankClient;
}
```

### 3.2 修改 retrieve() 方法的截断逻辑

**当前代码（第 76-94 行）：**
```java
List<MergeBucket> ranked = merged.values().stream()
        .sorted(Comparator.comparingDouble(MergeBucket::rrfScore).reversed())
        .limit(ragProperties.getFinalTopN())
        .toList();
// 后续构造 RetrievedChunk...
```

**改后逻辑：**

```java
// 1. 判断 rerank 是否启用
boolean rerankEnabled = ragProperties.getRetrieval() != null
        && ragProperties.getRetrieval().getRerank() != null
        && ragProperties.getRetrieval().getRerank().isEnabled()
        && rerankClient != null;

// 2. 计算 effectiveFinalTopN（优先用 rerank.finalTopN，回退全局）
int effectiveFinalTopN = ragProperties.getFinalTopN();
if (rerankEnabled && ragProperties.getRetrieval().getRerank().getFinalTopN() != null) {
    effectiveFinalTopN = ragProperties.getRetrieval().getRerank().getFinalTopN();
}

// 3. 取 candidateSize 个候选（而非直接 finalTopN）
int candidateSize = rerankEnabled
        ? ragProperties.getRetrieval().getRerank().getCandidateSize()
        : effectiveFinalTopN;

List<MergeBucket> candidates = merged.values().stream()
        .sorted(Comparator.comparingDouble(MergeBucket::rrfScore).reversed())
        .limit(candidateSize)
        .toList();

// 4. Rerank 分支
if (rerankEnabled && !candidates.isEmpty()) {
    candidates = applyRerank(query, candidates, effectiveFinalTopN,
            ragProperties.getRetrieval().getRerank().getModel());
} else {
    // 未启用 rerank 时截断到 finalTopN
    candidates = candidates.subList(0, Math.min(effectiveFinalTopN, candidates.size()));
}

// 5. 后续构造 RetrievedChunk 列表不变...
```

### 3.3 新增私有方法 applyRerank

```java
private List<MergeBucket> applyRerank(String query, List<MergeBucket> candidates,
                                      int topN, String model) {
    List<String> docs = candidates.stream().map(MergeBucket::content).toList();
    Optional<List<RerankResult>> rerankOpt = rerankClient.rerank(
            abbreviateQuery(query), docs, topN, model);
    
    if (rerankOpt.isPresent()) {
        List<RerankResult> results = rerankOpt.get();
        List<MergeBucket> reranked = new ArrayList<>(results.size());
        for (RerankResult r : results) {
            reranked.add(candidates.get(r.index()));
        }
        log.info("Rerank 完成：{} 候选 → top {}（用时由 RerankClient 日志体现）",
                candidates.size(), results.size());
        return reranked;
    } else {
        // fail-open：保持 RRF 序截断
        log.warn("Rerank 调用失败，回退 RRF 序");
        return candidates.subList(0, Math.min(topN, candidates.size()));
    }
}
```

### 3.4 单元测试改动

新增测试用例（已有其他测试保持不变）：

**Test: rerank 关闭时保持原逻辑**
- Mock `RagProperties` 的 `rerank.enabled=false`
- 调用 `retrieve()`
- 断言返回结果与未改动前一致（按 RRF 分数排序 top 5）

**Test: rerank 开启且成功**
- Mock `RagProperties` 的 `rerank.enabled=true, candidateSize=10, finalTopN=3`
- Mock `RerankClient.rerank()` 返回 `Optional.of([RerankResult(5, 0.9), ...])`（index 打乱）
- 断言返回结果按 rerank 顺序排列，长度=3

**Test: rerank 失败回退**
- Mock `RerankClient.rerank()` 返回 `Optional.empty()`
- 断言返回结果退回 RRF 序 top 3

**验收：**
- [ ] `./mvnw test -Dtest=HybridRetrieverTest` 全绿
- [ ] 日志中有 "Rerank 完成" 或 "Rerank 调用失败，回退 RRF 序"

---

## Task 4: AgentContext 新增 originalQuery 字段

**目标：** 为终答 RAG 合成提供原始查询（当前 ctx 无法从 messages 提取）。

**产出文件：**
- `src/main/java/com/agenticrag/agent/AgentContext.java`（修改）
- `src/main/java/com/agenticrag/service/ChatService.java`（修改）

**详细步骤：**

### 4.1 修改 AgentContext

新增字段：
```java
private final String originalQuery;  // 原始用户查询（用于终答 RAG 合成）
```

修改构造函数：
```java
public AgentContext(List<ChatMessage> messages, List<ToolSchema> availableTools,
                    int maxRounds, String originalQuery) {
    // ... 现有赋值
    this.originalQuery = originalQuery;
}

public String getOriginalQuery() {
    return originalQuery;
}
```

### 4.2 修改 ChatService.runAgent()

修改第 186 行构造 `AgentContext` 的代码：
```java
// 从 memory 的最后一条 user 消息提取原始查询
String originalQuery = userMessage;  // userMessage 已是最新用户输入

AgentContext ctx = new AgentContext(
        buildAgentMessages(sessionId),
        toToolSchemas(),
        llmProperties.getMaxAgentRounds(),
        originalQuery  // 新增参数
);
```

**验收：**
- [ ] `./mvnw compile` 成功
- [ ] 运行 smoke test `chat agent mode` 无报错

---

## Task 5: AgentLoop 终答泄漏检测与 RAG 合成降级

**目标：** 在两个终答分支检测 `<tool_call>` 泄漏，触发时用累积 sources 合成干净答案。

**产出文件：**
- `src/main/java/com/agenticrag/agent/AgentLoop.java`（修改）
- `src/test/java/com/agenticrag/agent/AgentLoopTest.java`（新增）
- `src/main/java/com/agenticrag/service/ChatService.java`（间接依赖，需注入 RagProperties）

**详细步骤：**

### 5.1 AgentLoop 新增依赖

构造函数注入：
```java
private final RagProperties ragProperties;
private final RerankClient rerankClient;  // 可选

public AgentLoop(LlmClient llmClient,
                 ToolRegistry toolRegistry,
                 LlmProperties properties,
                 ToolSchemaValidator toolSchemaValidator,
                 RagProperties ragProperties,
                 @Autowired(required = false) RerankClient rerankClient) {
    // ...
    this.ragProperties = ragProperties;
    this.rerankClient = rerankClient;
}
```

### 5.2 修改 run() 方法的两个终答分支

**分支 1（第 47-52 行，正常终答）：**
```java
if (response.toolCalls() == null || response.toolCalls().isEmpty()) {
    ctx.recordState(AgentState.FINAL);
    ctx.addMessage(ChatMessage.assistant(response.content()));
    reporter.onFinal("生成最终回答");
    
    // 新增：泄漏检测与修复
    String sanitized = sanitizeFinalAnswer(ctx, response.content());
    return sanitized;
}
```

**分支 2（第 69-78 行，超轮次终答）：**
```java
ctx.recordState(AgentState.FINAL);
reporter.onFinal("达到最大轮次，强制输出");
try {
    String finalContent = llmClient.chat(ctx.getMessages());
    ctx.addMessage(ChatMessage.assistant(finalContent));
    
    // 新增：泄漏检测与修复
    String sanitized = sanitizeFinalAnswer(ctx, finalContent);
    return sanitized;
} catch (Exception e) {
    // ... 现有异常处理不变
}
```

### 5.3 新增私有方法 sanitizeFinalAnswer

```java
/**
 * 检测终答是否泄漏工具调用标记，若泄漏则用累积 sources 重新生成干净答案。
 */
private String sanitizeFinalAnswer(AgentContext ctx, String rawAnswer) {
    if (rawAnswer == null || !rawAnswer.contains("<tool_call")) {
        return rawAnswer;  // 无泄漏，直接返回
    }
    
    log.warn("检测到终答泄漏（含 <tool_call> 标记），触发 RAG 合成降级");
    
    List<RetrievedChunk> accumulated = ctx.getSources();
    if (accumulated == null || accumulated.isEmpty()) {
        log.warn("累积 sources 为空，无法降级，返回原始终答");
        return rawAnswer;
    }
    
    // 去重（按 documentId 保留首次出现）
    List<RetrievedChunk> deduped = deduplicateByDocumentId(accumulated);
    
    // 可选 rerank 剪枝
    List<RetrievedChunk> pruned = rerankPruneIfEnabled(ctx.getOriginalQuery(), deduped);
    
    // RAG 合成
    try {
        return synthesizeRagAnswer(ctx.getOriginalQuery(), pruned);
    } catch (Exception e) {
        log.warn("RAG 合成失败", e);
        return "抱歉，处理失败，请重试。";
    }
}
```

### 5.4 新增辅助方法

**deduplicateByDocumentId:**
```java
private List<RetrievedChunk> deduplicateByDocumentId(List<RetrievedChunk> chunks) {
    Map<Long, RetrievedChunk> seen = new LinkedHashMap<>();
    for (RetrievedChunk chunk : chunks) {
        seen.putIfAbsent(chunk.documentId(), chunk);
    }
    return new ArrayList<>(seen.values());
}
```

**rerankPruneIfEnabled:**
```java
private List<RetrievedChunk> rerankPruneIfEnabled(String query, List<RetrievedChunk> chunks) {
    boolean rerankEnabled = ragProperties.getRetrieval() != null
            && ragProperties.getRetrieval().getRerank() != null
            && ragProperties.getRetrieval().getRerank().isEnabled()
            && rerankClient != null;
    
    if (!rerankEnabled || chunks.isEmpty()) {
        // 未启用 rerank：保留前 5-8 个（可配置为 8）
        return chunks.subList(0, Math.min(8, chunks.size()));
    }
    
    // 调 rerank 取 top 8
    List<String> docs = chunks.stream().map(RetrievedChunk::content).toList();
    Optional<List<RerankResult>> rerankOpt = rerankClient.rerank(query, docs, 8,
            ragProperties.getRetrieval().getRerank().getModel());
    
    if (rerankOpt.isPresent()) {
        return rerankOpt.get().stream()
                .map(r -> chunks.get(r.index()))
                .toList();
    } else {
        log.warn("Agent 终答降级中的 rerank 失败，保留原序 top 8");
        return chunks.subList(0, Math.min(8, chunks.size()));
    }
}
```

**synthesizeRagAnswer:**
```java
private String synthesizeRagAnswer(String query, List<RetrievedChunk> chunks) {
    // 构造 RAG system prompt（复用 ChatService 的模板）
    StringBuilder context = new StringBuilder();
    context.append("你是一个基于给定上下文回答问题的中文助手。回答规则：\n"
            + "1. 优先利用给定上下文作答，句末以 [n] 标注引用来源；\n"
            + "2. 关键细节（编号、日期、数值、阈值、名称等）必须按原文精确复述，不得省略或改写；\n"
            + "3. 若问题包含多个子项，必须逐项回答，不可遗漏任何一项；\n"
            + "4. 若上下文信息不完整，基于现有上下文尽力作答，对推测部分以（不确定）标注，不要直接拒答。\n\n");
    
    for (int i = 0; i < chunks.size(); i++) {
        RetrievedChunk chunk = chunks.get(i);
        context.append("[").append(i + 1).append("] ")
                .append(chunk.docName()).append("：")
                .append(chunk.content()).append("\n\n");
    }
    
    List<ChatMessage> messages = List.of(
            ChatMessage.system(context.toString()),
            ChatMessage.user(query)
    );
    
    return llmClient.chat(messages);
}
```

### 5.5 单元测试

新增 `AgentLoopTest.java`，测试三路径：

**Test 1: 无泄漏**
- Mock `LlmClient.chatWithTools()` 返回正常终答（不含 `<tool_call>`）
- 断言返回内容 == 原始终答

**Test 2: 泄漏且有 sources**
- Mock `chatWithTools()` 返回含 `<tool_call>` 的终答
- Mock `AgentContext.getSources()` 返回 3 个 chunks
- Mock `LlmClient.chat()` 返回干净答案
- 断言返回内容 == 干净答案，且日志包含 "检测到终答泄漏"

**Test 3: 泄漏但 sources 为空**
- Mock `chatWithTools()` 返回含 `<tool_call>` 的终答
- Mock `getSources()` 返回空列表
- 断言返回内容 == 原始终答（因无 sources 可用）

**验收：**
- [ ] `./mvnw test -Dtest=AgentLoopTest` 全绿
- [ ] 日志验证路径覆盖

---

## Task 6: 配置文件与环境变量

**目标：** 完善 `application.yaml` 与评测脚本的环境变量透传。

**产出文件：**
- `src/main/resources/application.yaml`（修改）
- `scripts/run-erag-round.sh`（修改）

**详细步骤：**

### 6.1 修改 application.yaml

在 `rag.retrieval` 段新增：
```yaml
rag:
  retrieval:
    # ... 现有配置
    rerank:
      enabled: ${RAG_RETRIEVAL_RERANK_ENABLED:false}
      model: ${RAG_RETRIEVAL_RERANK_MODEL:BAAI/bge-reranker-v2-m3}
      candidate-size: ${RAG_RETRIEVAL_RERANK_CANDIDATE_SIZE:20}
      final-top-n: ${RAG_RETRIEVAL_RERANK_FINAL_TOP_N:}  # 空则回退 retrieval.final-top-n
```

### 6.2 修改 run-erag-round.sh

在脚本开头注释中补充 rerank 环境变量说明：
```bash
# 参数：
#   $1 round          轮次名 (r01-r06, r08, r07b 等)
#   $2 mode           检索模式 (hybrid/vector/bm25)
#   $3 rewriter       可选，1=启用 QueryRewriter
#   $4 hyde           可选，1=启用 HyDE
#   $5 rerank         可选，1=启用 Rerank（新增）
```

脚本 export 段新增：
```bash
RERANK_ENABLED=${5:-0}
export RAG_RETRIEVAL_RERANK_ENABLED=$RERANK_ENABLED
# 其他 rerank 参数使用默认值，如需定制可再加环境变量
```

**验收：**
- [ ] 启动应用，`application.yaml` 解析无报错
- [ ] 运行 `./scripts/run-erag-round.sh r99 hybrid 0 0 1`，日志显示 rerank.enabled=true

---

## Task 7: 集成测试与 r08 评测

**目标：** 用真实硅基流动 API 验证 rerank 端到端流程，跑 r08 评测验证指标。

**详细步骤：**

### 7.1 手工 smoke test（可选，快速验证）

```bash
cd /Users/miaobao/agent-projects/agentic-rag

# 启用 rerank，RAG 模式单次查询
export RAG_RETRIEVAL_RERANK_ENABLED=1
export RAG_RETRIEVAL_MODE=hybrid
export RAG_RETRIEVAL_REWRITER_ENABLED=1
export RAG_RETRIEVAL_HYDE_ENABLED=1

# 启动应用（非评测模式），交互式输入一个查询
./mvnw spring-boot:run

# 观察日志是否包含 "Rerank 完成：20 候选 → top 5"
```

### 7.2 r08 完整评测

```bash
# r08 = r06 配置 + rerank 启用
./scripts/run-erag-round.sh r08 hybrid 1 1 1

# 等待 64 题作答完成（约 1.5 小时），检查无超时
tail -f /tmp/r08-作答.log | grep -E "FAILED|timeout"

# 判分
./scripts/run-erag-judge.sh r08

# 检查判分无网络污染
grep APIConnectionError eval-answers/results-erag-rounds-era2-r08.json
# 应返回空（exit code 1）

# 查看成绩
jq '.overall_metrics' eval-answers/results-erag-rounds-era2-r08.json
```

**预期指标（对比 r06）：**
| 指标 | r06 基线 | r08 预期 | 说明 |
|------|---------|---------|------|
| Document Recall | 66.20% | 不变或略降（检索结构未变） | Rerank 改变排序但不改变召回集 |
| Completeness | 50.53% | 不变或略升 | 生成层指标，rerank 影响间接 |
| Correctness | 35.94% | **≥40%** | 关键验收：噪声文档下沉 → 生成质量提升 |
| Invalid Extra | 3.09 | **≤1.5** | 关键验收：冲突陷阱文档被精排过滤 |

**验收：**
- [ ] r08 作答 64 题，零超时、零 FAILED
- [ ] 判分零网络污染
- [ ] Invalid extra ≤ 1.5
- [ ] Correctness ≥ 40%
- [ ] 日志中有 "Rerank 完成" 记录（说明 rerank 生效）

---

## Task 8: r07b AGENT 模式评测与终答泄漏验证

**目标：** 验证终答泄漏修复有效性，r07b 零题泄漏，invalid extra 从 10.27 降至 <3。

**详细步骤：**

### 8.1 r07b 完整评测

```bash
# r07b = r07 配置 + rerank + 终答修复生效
./scripts/run-erag-round.sh r07b agent 1 1 1

# 等待作答（AGENT 模式多轮检索，耗时约 2-3 小时）
tail -f /tmp/r07b-作答.log

# 判分
./scripts/run-erag-judge.sh r07b

# 检查判分无污染
grep APIConnectionError eval-answers/results-erag-rounds-era2-r07b.json
```

### 8.2 泄漏检测

```bash
# 扫描全部 64 题答案，统计含 <tool_call> 的题数
grep -c '<tool_call' eval-answers/rounds-era2/r07b.jsonl
# 预期输出 0（零题泄漏）

# 对比 r07（已知 41 题泄漏）
grep -c '<tool_call' eval-answers/rounds-era2/r07.jsonl
# 应输出 41
```

### 8.3 查看成绩

```bash
jq '.overall_metrics' eval-answers/results-erag-rounds-era2-r07b.json
```

**预期指标：**
| 指标 | r07（泄漏版）| r07b 预期 | 说明 |
|------|-------------|----------|------|
| Document Recall | 75.22% | 不变或略升（多轮检索优势保持） | |
| Correctness | 32.81%（作废）| **≥38%** | 有效基线，泄漏修复后正常发挥 |
| Invalid Extra | 10.27 | **≤3.0** | 关键验收：多轮噪声被 rerank 控制 |
| 泄漏题数 | 41/64 | **0/64** | 最关键验收：终答修复完全生效 |

**验收：**
- [ ] r07b 作答 64 题，零 FAILED
- [ ] `grep '<tool_call' r07b.jsonl` 输出为空（零题泄漏）
- [ ] Invalid extra ≤ 3.0
- [ ] Correctness ≥ 38%
- [ ] 日志中有 "检测到终答泄漏" WARN 记录（证明降级逻辑触发）

---

## Task 9: 更新 README 与复盘文档

**目标：** 记录 r08/r07b 成绩，总结 rerank 增益与终答修复效果。

**产出文件：**
- `README.md`（修改）
- `docs/plan/M6/2026-09-16-M6性能优化方案-BM25修复与泛化性建设.md`（修改）
- `docs/issues/2026-09-17-RAG优化复盘-通俗版.md`（可选）

**详细步骤：**

### 9.1 更新 README 评测表格

在 `## EnterpriseRAG-Bench 评测` 段新增 r08/r07b 两行：

| Round | Mode | Config | Recall | Comp | Corr | Invalid | 答对 |
|-------|------|--------|--------|------|------|---------|------|
| ... | ... | ... | ... | ... | ... | ... | ... |
| r06 | RAG | hybrid+rw+hyde | 66.20 | 50.53 | 35.94 | 3.09 | 23/64 |
| **r08** | RAG | r06+**rerank** | X.XX | X.XX | **X.XX** | **X.XX** | X/64 |
| r07 | AGENT | hybrid+rw+hyde | 75.22 | 43.50 | 32.81† | 10.27 | 21/64 |
| **r07b** | AGENT | r07+rerank+**修复泄漏** | X.XX | X.XX | **X.XX** | **X.XX** | X/64 |

注：†表示 r07 的 32.81% 因 41 题泄漏作废，r07b 为有效基线。

### 9.2 更新优化方案文档

在 `2026-09-16-M6性能优化方案-BM25修复与泛化性建设.md` 的 Phase 6 后新增 **Phase 7: Rerank 精排与终答修复**：

```markdown
## Phase 7: Rerank 精排与 Agent 终答修复（已完成）

**实施时间：** 2026-09-19

**改动：**
1. 接入硅基流动 `BAAI/bge-reranker-v2-m3` rerank 模型（免费、Cohere 兼容）
2. `HybridRetriever`：RRF 融合后 top 20 → rerank → top 5，fail-open 降级
3. `AgentLoop`：检测终答 `<tool_call>` 泄漏 → 用累积 sources + rerank 剪枝 → RAG 合成干净答案

**实测结果：**
- **r08（RAG + rerank）**：invalid extra 3.09 → X.XX（-XX%），correctness 35.94% → XX.XX%（+X.Xpp）
- **r07b（AGENT + 修复）**：泄漏 41 题 → 0 题，invalid extra 10.27 → X.XX（-XX%），correctness 有效化
```

### 9.3 可选：通俗版复盘补充

在 `2026-09-17-RAG优化复盘-通俗版.md` 末尾新增 **第七幕：精排管家**（可选，视篇幅决定）。

**验收：**
- [ ] README 表格填入 r08/r07b 真实数字
- [ ] 优化方案文档 Phase 7 段落完整
- [ ] git diff 检查无遗漏

---

## 最终验收 Checklist

勾选所有任务的验收点后，M6 rerank 改造完成：

**代码层面：**
- [ ] `./mvnw clean compile` 成功，零编译错误
- [ ] `./mvnw test` 全绿（RerankClientTest、HybridRetrieverTest、AgentLoopTest）
- [ ] 代码审查：RerankClient <150 行，HybridRetriever rerank 分支 <30 行，AgentLoop 终答修复 <80 行

**评测层面：**
- [ ] r08：64 题作答+判分完成，零网络污染
- [ ] r08 invalid extra ≤ 1.5，correctness ≥ 40%
- [ ] r07b：64 题作答+判分完成，零网络污染
- [ ] r07b 泄漏 0 题，invalid extra ≤ 3.0，correctness ≥ 38%

**文档层面：**
- [ ] README 更新 r08/r07b 成绩
- [ ] 优化方案文档补充 Phase 7
- [ ] spec/plan/task 三文档归档于 `docs/plan/M6/`

---

**task.md 完成。所有子任务按依赖顺序排列，单独可验收。请审批后开始实施。**

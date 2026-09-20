# Reranker 接入与 Agent 终答修复 Plan

> 基于已批准的 spec.md，定义架构、核心数据结构、模块交互与技术决策。

## 一、架构概览

改动涉及三个模块，职责分明、依赖单向：

```
                ┌─────────────────────────┐
                │     RerankClient        │  新增：硅基流动 /v1/rerank HTTP 客户端
                │  (rag/retrieve/)        │
                └──────────┬──────────────┘
                           │ 调用
                ┌──────────▼──────────────┐
                │    HybridRetriever      │  修改：RRF 后插入 rerank 分支
                │  (rag/retrieve/)        │
                └──────────┬──────────────┘
                           │ 被调用
          ┌────────────────┼────────────────┐
          │                │                │
┌─────────▼─────┐ ┌───────▼───────┐ ┌──────▼──────────┐
│  ChatService  │ │ AgentLoop     │ │ SearchKnowledge │
│  .runRag()    │ │ .run()  修改  │ │ BaseTool        │
│  不改动       │ │ 终答泄漏修复  │ │ 不改动          │
└───────────────┘ └───────────────┘ └─────────────────┘
```

**数据流（RAG 模式，rerank 开启）：**
```
query → QueryRewriter.expand() → 多路 searchAndMerge()
      → RRF 融合 Map<chunkId, MergeBucket>
      → 按 rrfScore 降序取 top candidateSize(20)
      → RerankClient.rerank(query, chunks)           ← 新增
      → 按 relevanceScore 降序取 top finalTopN(5)    ← 替换原逻辑
      → List<RetrievedChunk> → 送生成
```

**数据流（AGENT 模式，终答泄漏时）：**
```
AgentLoop.run() → toolCalls.isEmpty() → content 含 <tool_call>？
  → 是：从 ctx.getSources() 获取累积 chunks
       → 去重（按 documentId 保留首次出现）
       → RerankClient.rerank(原始 query, 去重 chunks)  ← 如果 rerank 启用
       → 取 top 5-8
       → 构造 RAG system prompt + query → llmClient.chat() → 返回新答案
  → 否：正常返回 content
```

## 二、核心数据结构

### 2.1 RerankResult（新增 record）

```java
package com.agenticrag.rag.retrieve;

/**
 * 单个重排结果：原始索引 + 相关性分数（0-1，已由 API 降序排列）。
 */
public record RerankResult(int index, double relevanceScore) {}
```

### 2.2 RagProperties.Rerank（新增配置子类）

```java
// 嵌套在 RagProperties.Retrieval 内部
@Data
public static class Rerank {
    /** 总开关（默认关闭） */
    private boolean enabled = false;
    /** 模型名 */
    private String model = "BAAI/bge-reranker-v2-m3";
    /** 送 rerank 的候选数量 */
    private int candidateSize = 20;
    /** 精排后保留数量（null 时复用 retrieval.finalTopN） */
    private Integer finalTopN;
}
```

## 三、模块设计

### 3.1 RerankClient（新增）

**职责：** 调用硅基流动 `/v1/rerank` 端点，返回按相关性降序排列的索引+分数。

**位置：** `src/main/java/com/agenticrag/rag/retrieve/RerankClient.java`

**对外接口：**
```java
@Component
public class RerankClient {
    /**
     * 对候选文档列表重排序。
     * @param query      原始查询
     * @param documents  候选文档文本列表
     * @param topN       返回前 N 个
     * @param model      模型名
     * @return Optional<List<RerankResult>>，empty 表示调用失败（fail-open）
     */
    public Optional<List<RerankResult>> rerank(String query, List<String> documents,
                                               int topN, String model);
}
```

**内部设计（参照 EmbeddingClient）：**
- 复用 `LlmProperties.baseUrl` + `LlmProperties.apiKey`（同一硅基流动账号）
- HTTP 客户端：`java.net.http.HttpClient`，DCL 懒初始化，与 EmbeddingClient 同一模式
- 超时：`LlmProperties.timeoutSeconds`（默认 120s）
- 重试：网络异常重试 1 次（间隔 1s），4xx/5xx 不重试
- 失败时记 WARN 日志并返回 `Optional.empty()`（不抛异常）
- 请求体：`{"model":"...","query":"...","documents":[...],"top_n":N,"return_documents":false}`
- 响应解析：`results[]` 数组，每项 `{index, relevance_score}`

**依赖：** `LlmProperties`

### 3.2 HybridRetriever（修改）

**改动位置：** `retrieve()` 方法，RRF 排序之后、构造 `RetrievedChunk` 列表之前。

**当前逻辑（第 76-94 行）：**
```java
List<MergeBucket> ranked = merged.values().stream()
    .sorted(descending by rrfScore)
    .limit(ragProperties.getFinalTopN())   // ← 直接截断
    .toList();
```

**改后逻辑：**
```java
// 1. 先取 candidateSize 个候选（而非直接 finalTopN）
int candidateSize = rerankEnabled
    ? ragProperties.getRetrieval().getRerank().getCandidateSize()
    : effectiveFinalTopN;
List<MergeBucket> candidates = merged.values().stream()
    .sorted(descending by rrfScore)
    .limit(candidateSize)
    .toList();

// 2. 若 rerank 启用，调 RerankClient 重排
if (rerankEnabled) {
    List<String> docs = candidates.stream().map(MergeBucket::content).toList();
    Optional<List<RerankResult>> rerankOpt = rerankClient.rerank(query, docs, effectiveFinalTopN, model);
    if (rerankOpt.isPresent()) {
        // 按 rerank 返回顺序重建 candidates
        candidates = rerankOpt.get().stream()
            .map(r -> candidates.get(r.index()))
            .toList();
    } else {
        // fail-open：保持 RRF 序截断
        candidates = candidates.subList(0, Math.min(effectiveFinalTopN, candidates.size()));
    }
} else {
    candidates = candidates.subList(0, Math.min(effectiveFinalTopN, candidates.size()));
}
```

**构造函数：** 注入 `RerankClient`（可选，`@Autowired(required=false)` 避免强依赖）。

**依赖：** `RerankClient`、`RagProperties`

### 3.3 AgentLoop（修改）

**改动位置：** `run()` 方法的两个终答分支。

**分支 1（正常终答，第 47-52 行）：** `toolCalls.isEmpty()` 时，新增泄漏检测。

**分支 2（超轮次终答，第 69-78 行）：** `llmClient.chat()` 返回后，同样检测。

**泄漏检测与降级（抽为私有方法 `sanitizeFinalAnswer`）：**
```java
private String sanitizeFinalAnswer(AgentContext ctx, String rawAnswer) {
    if (rawAnswer == null || !rawAnswer.contains("<tool_call")) {
        return rawAnswer;  // 无泄漏，直接返回
    }
    log.warn("检测到终答泄漏（含 <tool_call> 标记），触发 RAG 合成降级");

    // 从 ctx.getSources() 获取已累积的 chunks（SearchKnowledgeBaseTool 已写入）
    List<RetrievedChunk> accumulated = ctx.getSources();
    if (accumulated == null || accumulated.isEmpty()) {
        log.warn("累积 sources 为空，无法降级，返回原始终答");
        return rawAnswer;
    }

    // 按 documentId 去重（保留首次出现的 chunk）
    List<RetrievedChunk> deduped = deduplicateByDocId(accumulated);

    // 可选 rerank 剪枝（如果 rerank 启用）
    List<RetrievedChunk> pruned = rerankPruneIfEnabled(ctx.getOriginalQuery(), deduped);

    // 构造 RAG prompt + 调 LLM 生成终答
    return synthesizeRagAnswer(ctx.getOriginalQuery(), pruned);
}
```

**注意：** spec 约定了用选项 2（从消息解析 sources），但代码审查发现 `AgentLoop.executeTool()` 已有 `ctx.addSources(chunks)`，`ctx.getSources()` 即是累积结果。直接用 `ctx.getSources()` 比解析消息更可靠、更简洁。这是一处优于 spec 原案的优化——改动更小、无 JSON 解析失败风险。

**新增依赖：** `RerankClient`（通过构造函数注入）、`RagProperties`（读 rerank 配置）、`LlmClient`（RAG 合成调用）。

**AgentContext 变更：** 需新增 `originalQuery` 字段（当前 ctx 中无原始 query，仅有 messages 列表），在 `ChatService.runAgent()` 构造 ctx 时传入。

### 3.4 RagProperties（修改）

```java
@Data
public static class Retrieval {
    // ... 现有字段
    private Rerank rerank = new Rerank();  // 新增
}
```

### 3.5 application.yaml（修改）

```yaml
rag:
  retrieval:
    rerank:
      enabled: ${RAG_RETRIEVAL_RERANK_ENABLED:false}
      model: ${RAG_RETRIEVAL_RERANK_MODEL:BAAI/bge-reranker-v2-m3}
      candidate-size: ${RAG_RETRIEVAL_RERANK_CANDIDATE_SIZE:20}
      final-top-n: ${RAG_RETRIEVAL_RERANK_FINAL_TOP_N:}  # 空=复用 retrieval.final-top-n
```

## 四、模块交互

```
调用链（RAG 模式 r08）:
ChatService.runRag()
  → HybridRetriever.retrieve(query)
    → QueryRewriter.expand(query) → 多路 searchAndMerge()
    → RRF 融合 → top 20 candidates
    → RerankClient.rerank(query, 20 docs, top 5)    ← 新增
    → List<RetrievedChunk> top 5
  → buildMessages(chunks) → llmClient → answer

调用链（AGENT 模式 r07b，泄漏触发时）:
ChatService.runAgent()
  → AgentLoop.run(ctx)
    → [多轮工具循环…]
    → toolCalls.isEmpty() → content 含 <tool_call>？
      → sanitizeFinalAnswer(ctx, rawAnswer)
        → ctx.getSources() → deduplicateByDocId()
        → RerankClient.rerank(query, deduped, top 8)  ← 新增
        → synthesizeRagAnswer(query, pruned)           ← 新增
          → LlmClient.chat(RAG prompt + query)
        → 返回生成的干净答案
  → ChatService 包装为 ChatResult
```

## 五、文件组织

```
src/main/java/com/agenticrag/
├── agent/
│   ├── AgentLoop.java          修改：终答泄漏检测 + RAG 合成降级
│   └── AgentContext.java       修改：新增 originalQuery 字段
├── config/
│   └── RagProperties.java      修改：Retrieval 内嵌 Rerank 配置子类
├── rag/retrieve/
│   ├── HybridRetriever.java    修改：RRF 后插入 rerank 分支
│   ├── RerankClient.java       新增：硅基流动 /v1/rerank 客户端
│   └── RerankResult.java       新增：重排结果 record
├── service/
│   └── ChatService.java        修改：构造 AgentContext 时传入 originalQuery
src/main/resources/
│   └── application.yaml        修改：新增 rerank 配置段
src/test/java/com/agenticrag/
├── rag/retrieve/
│   ├── RerankClientTest.java   新增：mock 成功/失败/超时
│   └── HybridRetrieverTest.java 修改：rerank 开/关/失败回退
├── agent/
│   └── AgentLoopTest.java      新增：泄漏检测三路径
scripts/
│   └── run-erag-round.sh       修改：透传 rerank 环境变量
```

## 六、技术决策

| 决策点 | 选择 | 理由 |
|--------|------|------|
| Sources 累积来源 | `ctx.getSources()`（AgentContext 已有） | spec 约定消息解析，但代码审查发现 ctx 已累积（`executeTool` 中 `ctx.addSources`），更可靠且零解析风险 |
| RerankClient 包归属 | `rag/retrieve/` | 与 HybridRetriever 同包，检索层内聚 |
| HTTP 客户端 | `java.net.http.HttpClient` | 与 EmbeddingClient 一致，无新依赖 |
| fail-open 粒度 | 返回 `Optional.empty()` | 调用方决定降级策略，客户端只管调用与报告 |
| rerank finalTopN | 独立配置，null 时回退全局 | r08 消融需要对比 finalTopN=5 vs 8，不能绑死全局值 |
| 终答泄漏修复范围 | 同时覆盖正常终答 + 超轮次终答 | 两个分支都可能泄漏（超轮次分支撤掉工具后同理） |
| originalQuery 传递 | AgentContext 新增字段 | 终答 RAG 合成需要原始 query，ctx.messages 首条是 system prompt 无法提取 |

---

**plan.md 已完成。请 review：**
- 架构划分是否合理？
- `ctx.getSources()` 替代消息解析是否认同？（比 spec 原案更优）
- 核心接口定义是否完整？
- 技术决策是否认同？

**确认后进入 task.md 任务拆解阶段。**

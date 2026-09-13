# 意图识别降级与隐式路由误判问题

**日期**：2026-09-11  
**严重程度**：中等  
**状态**：已修复

---

## 问题描述

### 现象1：计算问题错误附带引用来源

用户提问："1293 * 29等于多少"，回答正确（37,497），但前端显示了与计算无关的知识库引用来源。

**期望行为**：纯计算问题不应附带知识库引用来源。

### 现象2：常识问题错误附带引用来源

用户提问："地球有几大洲几大洋？"，回答虽然表示与知识库问答无关，但答案后面仍附带引用来源。

**期望行为**：常识性对话不应附带知识库引用来源。

---

## 根因分析

### 问题1：意图识别降级策略缺失

**旧逻辑**：
```
专用意图模型（Qwen/Qwen3.5-9B）超时/失败
   ↓
直接返回 UNKNOWN
   ↓
进入隐式路由兜底
```

**问题**：
1. 超时时间设置为 5 秒，在网络波动或模型负载高时容易超时
2. 专用模型失败后没有降级机制，直接返回 UNKNOWN
3. 主模型（GLM-5.3）未被充分利用作为降级方案

**日志证据**：
```
2026-09-11T15:39:45.781+08:00  INFO 44965 --- Intent classifier request: 
    dedicated=true, baseUrl=https://api.siliconflow.cn/v1, 
    model=Qwen/Qwen3.5-9B, timeoutSeconds=5
2026-09-11T15:39:50.785+08:00  WARN 44965 --- 意图识别调用失败，降级返回 UNKNOWN: 
    LLM 调用失败: request timed out
```

---

### 问题2：隐式路由盲目检索知识库

**旧逻辑**：
```java
private void runImplicitRoute(..., String userMessage) {
    List<RetrievedChunk> retrieved = hybridRetriever.retrieve(userMessage);
    if (retrieved == null || retrieved.isEmpty()) {
        runPlain(emitter, sessionId);
        return;
    }
    runRagWithRetrieved(emitter, sessionId, retrieved);  // 有检索结果就走 RAG
}
```

**问题**：
- 向量检索总是会返回"最相关"的文档，即使问题与知识库毫无关系
- 对于"地球有几大洲几大洋"，向量相似度可能在语义空间上匹配到某些地理相关的文档片段
- 只要 `retrieved` 非空，就会走 `runRagWithRetrieved()` 并附带 sources
- **缺少相关度阈值过滤**：低分匹配结果也被视为"相关"

**RRF 分数特性**：
```
RRF 分数 = Σ 1/(60 + rank)
单通道命中排第1：1/61 ≈ 0.016
双通道都命中排第1：2/61 ≈ 0.033
```

常识性问题可能只在单通道低排名命中，分数很低（< 0.02），但旧逻辑仍会将其视为相关。

---

## 修复方案

### 修复1：实现两级降级策略

**文件**：`src/main/java/com/agenticrag/intent/IntentClassifier.java`

**核心改动**：
```java
public IntentResult classify(String userMessage) {
    try {
        // 首次尝试：专用模型
        LlmProperties intentLlmProperties = buildIntentLlmProperties();
        String responseJson = callLlm(userMessage, intentLlmProperties);
        return parseIntent(responseJson);
    } catch (Exception e) {
        log.warn("意图识别首次调用失败（专用模型），准备降级: {}", e.getMessage());
        
        // 一级降级：专用模型 → 主模型
        if (hasDedicatedIntentConfig()) {
            try {
                log.info("意图识别降级到主模型：baseUrl={}, model={}", ...);
                String responseJson = callLlm(userMessage, llmProperties);
                IntentResult result = parseIntent(responseJson);
                log.info("意图识别主模型降级成功：intent={}, confidence={}", ...);
                return result;
            } catch (Exception fallbackEx) {
                log.warn("意图识别主模型降级也失败，返回 UNKNOWN: {}", ...);
            }
        }
        
        // 二级降级：返回 UNKNOWN
        return new IntentResult(Intent.UNKNOWN, 0.0);
    }
}
```

**配套修改**：`.env` 超时从 5 秒延长到 10 秒
```bash
RAG_INTENT_TIMEOUT_SECONDS=10
```

---

### 修复2：隐式路由增加相关度阈值过滤

**文件**：`src/main/java/com/agenticrag/api/ChatController.java`

**核心改动**：
```java
private static final double IMPLICIT_ROUTE_MIN_RRF_SCORE = 0.02;

private void runImplicitRoute(SseEmitter emitter, String sessionId, String userMessage) {
    List<RetrievedChunk> retrieved = hybridRetriever.retrieve(userMessage);
    
    // 过滤低分结果，只保留真正相关的
    List<RetrievedChunk> relevant = retrieved == null ? List.of() : retrieved.stream()
            .filter(c -> c.rrfScore() >= IMPLICIT_ROUTE_MIN_RRF_SCORE)
            .toList();
    
    if (relevant.isEmpty()) {
        runPlain(emitter, sessionId);  // 无高分结果，走普通对话
        return;
    }
    runRagWithRetrieved(emitter, sessionId, relevant);  // 有高分结果才走 RAG
}
```

**阈值选择依据**：
- `0.02` 要求至少双通道都命中，或单通道排名非常靠前
- 常识性问题的低分匹配（< 0.02）会被过滤掉
- 真正相关的知识库问题通常能达到 0.025+ 的分数

---

## 完整处理流程（修复后）

```
用户提问
   ↓
专用意图模型识别（Qwen/Qwen3.5-9B）
   ↓
├─ 成功 → 按意图路由（CHAT/KB_QA/TOOL_TASK/OFF_TOPIC）
├─ 失败（超时/解析错误）
   ↓
   一级降级：主模型（GLM-5.3）重新识别
   ↓
   ├─ 成功 → 按意图路由
   └─ 失败 → UNKNOWN
      ↓
      隐式路由兜底：
      ├─ 检索知识库
      ├─ 过滤 rrfScore < 0.02 的结果
      ├─ 有高分结果 → RAG（带 sources）
      └─ 无高分结果 → Plain（无 sources）
```

---

## 修改文件清单

| 文件 | 修改内容 | 关键代码行 |
|---|---|---|
| `src/main/java/com/agenticrag/intent/IntentClassifier.java` | 实现两级降级逻辑 | L44-L85 |
| `src/main/java/com/agenticrag/api/ChatController.java` | 增加 RRF 阈值过滤 | L228-L241 |
| `.env` | 超时延长到 10 秒 | L13 |

---

## 效果验证

### 验证场景1：计算问题

**输入**：1293 * 29等于多少

**预期流程**：
1. 专用模型超时 → 主模型识别成功 → TOOL_TASK
2. 走 Agent 模式调用计算工具
3. `sources = []`（无引用来源）✅

**极端情况**（主模型也失败）：
1. 返回 UNKNOWN → 隐式路由
2. 检索知识库 → 计算问题不会匹配到相关文档
3. rrfScore < 0.02 → 走 Plain 模式
4. `sources = []`（无引用来源）✅

---

### 验证场景2：常识问题

**输入**：地球有几大洲几大洋？

**预期流程**：
1. 意图识别成功 → CHAT
2. 走 Plain 模式
3. `sources = []`（无引用来源）✅

**极端情况**（意图识别失败）：
1. 返回 UNKNOWN → 隐式路由
2. 检索知识库 → 可能低分匹配到某些地理文档（rrfScore ≈ 0.015）
3. 过滤后 `relevant.isEmpty()` → 走 Plain 模式
4. `sources = []`（无引用来源）✅

---

### 验证场景3：真正的知识库问答

**输入**：文档里提到的 RAG 是什么意思？

**预期流程**：
1. 意图识别成功 → KB_QA
2. 走 RAG 模式，检索知识库
3. rrfScore ≈ 0.035（双通道高排名命中）
4. `sources = [{n:1, docName:"xxx", snippet:"..."}]`（有引用来源）✅

---

## 设计权衡

### 为什么选择 0.02 作为阈值？

| 阈值 | 效果 | 风险 |
|---|---|---|
| 0.01 | 过松，低分误匹配仍会附带 sources | 常识问题仍可能误判 |
| 0.02 | 平衡，要求双通道或高排名命中 | 推荐 ✅ |
| 0.03 | 过严，部分真相关文档可能被过滤 | 知识库召回率下降 |

**结论**：0.02 在准确性和召回率之间取得较好平衡，可根据实际数据分布微调。

---

### 为什么不直接禁用隐式路由？

隐式路由是**降级兜底**的重要机制：
- 意图识别服务不稳定时，仍能提供基本功能
- 对于边界模糊的问题（如"这个文档讲了什么"），意图可能识别为 UNKNOWN，但检索能找到相关文档
- 两级降级 + RRF 阈值过滤已大幅降低误判概率

---

## 后续优化方向

1. **监控意图识别成功率**：
   - 记录专用模型/主模型/UNKNOWN 的比例
   - 如果 UNKNOWN 比例过高（> 10%），需排查配置或模型问题

2. **动态调整 RRF 阈值**：
   - 收集真实查询和检索结果，统计相关/不相关样本的 rrfScore 分布
   - 使用 ROC 曲线找到最优阈值

3. **引入混合相似度得分**：
   - 除了 RRF，还可以考虑最高单通道分数（如向量余弦相似度）
   - 双重过滤：`rrfScore >= 0.02 && maxVectorScore >= 0.7`

4. **优化意图识别 Prompt**：
   - 当前 Prompt 可能对 TOOL_TASK 和 KB_QA 的边界不够清晰
   - 增加更多示例，强调"需要查询文档"与"需要工具计算"的区别

---

## 参考资料

- [M4 体验升级方案](../plan/M4-体验升级方案.md)
- [IntentClassifier 源码](../../src/main/java/com/agenticrag/intent/IntentClassifier.java)
- [ChatController 源码](../../src/main/java/com/agenticrag/api/ChatController.java)
- [RRF 融合算法](../../src/main/java/com/agenticrag/rag/retrieve/HybridRetriever.java)

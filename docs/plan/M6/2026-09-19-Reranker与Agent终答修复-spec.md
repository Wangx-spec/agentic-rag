# Reranker 接入与 Agent 终答修复 Spec

> 2026-09-19。M6 性能优化阶段，针对 era-2 r06/r07 评测暴露的两个瓶颈：
> - **瓶颈 B（检索噪声）**：r06 RAG 模式 invalid extra 3.09，r07 AGENT 模式恶化至 10.27
> - **瓶颈 E（终答泄漏）**：r07 中 41/64 题的终答被 `<tool_call>` 标记污染
>
> 本 spec 定义完整解决方案：Reranker 基础设施 + AgentLoop 终答修复 + r08/r07b 评测验证。

## 一、背景

### 1.1 当前状态（era-2 r06/r07）

- **r06（RAG 模式，hybrid + rewriter + HyDE）**：recall 66.20% / correctness 35.94% / invalid extra 3.09
- **r07（AGENT 模式，同配置）**：recall 75.22%（+9pp，多轮检索打破结构天花板）/ correctness 32.81%（-3pp）/ invalid extra **10.27（×3.3）**

### 1.2 根因分析

**瓶颈 B（检索噪声）**：
- RRF 融合后仅保留 top-5，无精排，"48h vs 3 business days"类冲突陷阱文档混入生成上下文
- AGENT 模式多轮检索放大噪声累积（单题引用 2-19 个文档，无剪枝）

**瓶颈 E（终答泄漏，r07 特有）**：
- `AgentLoop.run()` 在最后一轮撤掉工具列表（`tools=[]`），GLM-5.3 看到历史消息中的 `<tool_call>` 格式，以纯文本模仿输出
- `response.toolCalls()` 为空被当作终答返回，41/64 题成绩作废

### 1.3 解决方案（来自可行性评估）

1. **Reranker 接入**：硅基流动 `BAAI/bge-reranker-v2-m3`（免费、Cohere 兼容、区分度 gold 0.344 vs 干扰 0.073），RRF 后 top-20 → rerank → top-N，fail-open 降级
2. **AgentLoop 终答修复**：检测泄漏 → 解析累积 sources → 去重 + rerank 剪枝 → 走 RAG prompt 重新生成
3. **评测验证**：r08（RAG 模式 + rerank 消融）、r07b（AGENT 模式修复版）

## 二、目标

1. **降低检索噪声**：r08 invalid extra 从 3.09 降至 <1.5；r07b 从 10.27 降至 <3
2. **修复终答泄漏**：r07b 中零题包含 `<tool_call>` 标记
3. **提升 correctness**：r08 预期 40-45%（vs r06 35.94%）；r07b 预期 38-42%（vs r07 作废的 32.81%）
4. **保持架构优雅**：fail-open、与现有 rewriter/HyDE 正交、配置开关可控

## 三、功能需求

### F1: Reranker 客户端

提供一个调用硅基流动 `/v1/rerank` 端点的客户端，接收查询文本和候选文档列表，返回按相关性降序排列的文档索引和分数。

**行为：**
- 输入：`query`(String)、`documents`(List<String>)、`topN`(int)、`model`(String)
- 输出：`List<RerankResult>`，每个包含 `index`(int) 和 `relevanceScore`(double)
- 协议：POST `/v1/rerank`，请求体 `{"model":"...","query":"...","documents":[...],"top_n":N,"return_documents":false}`
- 认证：复用 `LLM_API_KEY`（与 chat/embedding 共用）
- 超时：120 秒（与 embedding 对齐）
- 重试：网络错误重试 1 次，间隔 1 秒

**失败处理（fail-open）：**
- API 返回 4xx/5xx、超时、或响应解析失败 → 记 WARN 日志 + 返回 `Optional.empty()`
- 调用方检测到 empty 时保持原序（RRF 序）继续

### F2: HybridRetriever 接入 Reranker

在 `HybridRetriever.searchAndMerge()` 的 RRF 融合之后、截断 finalTopN 之前插入 rerank 精排。

**行为：**
- 配置开关 `rag.retrieval.rerank.enabled=false`（默认关闭），关闭时保持原逻辑不变
- 开启时：RRF 融合后取 top `candidateSize`(默认 20) → 调用 reranker → 按返回顺序取 top `finalTopN`
- 多路查询（rewriter/HyDE）各自独立 rerank，互不干扰
- rerank 失败时回退 RRF 序并记 WARN

**约束：**
- `candidateSize >= finalTopN`（配置校验，启动时检查）
- rerank 输入文档为 `chunk.content`（已在 RRF 阶段按 `documentId+chunkIndex` 去重）

### F3: AgentLoop 终答泄漏检测与修复

在 `AgentLoop.run()` 的终答分支（`toolCalls.isEmpty()`）新增泄漏检测：若 `response.content()` 包含 `<tool_call>` 或 `</tool_call>` 标记，触发 RAG 合成降级。

**泄漏检测：**
- 正则匹配：`<tool_call[^>]*>` 或 `</tool_call>`
- 命中时记 WARN 日志："检测到终答泄漏，触发 RAG 合成降级"

**降级流程：**
1. 从 `ctx.getMessages()` 解析所有 `role=tool` 且工具名为 `search_knowledge_base` 的 result JSON，提取 `sources[]` 字段
2. 合并所有 sources，按 `documentId` 去重（保留首次出现的 chunk）
3. 若启用 rerank：对去重后的 chunks 调用 reranker（用原始 query），取 top 5-8；若未启用或失败：保持累积顺序取前 5-8
4. 用剪枝后的 chunks + 原始 query 构造 RAG prompt（复用 `ChatService` 的 system prompt 模板），调用 LLM 生成终答
5. 返回新生成的答案

**边界情况：**
- 累积 sources 为空 → 跳过降级，返回原 `response.content()`（即使包含泄漏标记，因为无检索上下文可用）
- RAG 合成调用失败 → 返回兜底消息"抱歉，处理失败，请重试"

### F4: 配置与环境变量

新增配置项，所有配置均可通过环境变量覆盖（评测脚本需要）：

**Reranker 配置（`rag.retrieval.rerank.*`）：**
- `enabled`(boolean, 默认 false)：总开关
- `model`(String, 默认 `BAAI/bge-reranker-v2-m3`)：模型名
- `candidate-size`(int, 默认 20)：送 rerank 的候选数
- `final-top-n`(int, 默认 5)：精排后保留数（可独立于 `rag.retrieval.final-top-n` 设置，优先级更高）

**环境变量映射：**
- `RAG_RETRIEVAL_RERANK_ENABLED` → `rag.retrieval.rerank.enabled`
- `RAG_RETRIEVAL_RERANK_MODEL` → `rag.retrieval.rerank.model`
- `RAG_RETRIEVAL_RERANK_CANDIDATE_SIZE` → `rag.retrieval.rerank.candidate-size`
- `RAG_RETRIEVAL_RERANK_FINAL_TOP_N` → `rag.retrieval.rerank.final-top-n`

**启动校验：**
- 若 `rerank.enabled=true` 且 `candidate-size < final-top-n` → 启动失败，打印错误"candidateSize 必须 >= finalTopN"

### F5: 评测轮次验证

新增两轮评测验证改动有效性：

**r08（RAG 模式 + rerank 消融）：**
- 配置：r06 配置（hybrid + rewriter + HyDE）+ `rerank.enabled=true`
- 对比基线：r06
- 预期：invalid extra 3.09 → <1.5，correctness 35.94% → 40-45%

**r07b（AGENT 模式 + 终答修复）：**
- 配置：r07 配置（hybrid + rewriter + HyDE + agent）+ `rerank.enabled=true` + 终答修复生效
- 对比基线：r07（已知 41 题泄漏）
- 预期：零题泄漏，invalid extra 10.27 → <3，correctness 有效数字（不与 r07 作废数字比）

## 四、非功能需求

### NF1: 性能

- **延迟增量**：单次 rerank 调用 <1 秒（实测 0.47s/20 候选），对单次检索的总延迟增加可接受（<15% overhead）
- **批量评测**：64 题评测耗时增加 <30 分钟（单题 +0.5s × 3 路查询 = +1.5s，乘 64 题）

### NF2: 可观测性

- **INFO 日志**：rerank 开启时每次检索输出"Rerank 完成：20 候选 → top 5，耗时 Xms"
- **WARN 日志**：rerank 失败时"Rerank 调用失败：[原因]，回退 RRF 序"
- **WARN 日志**：终答泄漏检测命中时"检测到终答泄漏（含 <tool_call> 标记），触发 RAG 合成降级"
- **不记录敏感信息**：日志中不输出 API key、query 全文（可截断到 50 字符）

### NF3: 可测试性

- **单元测试**：RerankClient mock 硅基流动响应，测试成功/失败/超时三路径
- **单元测试**：HybridRetriever rerank 分支覆盖开关关闭/开启/失败回退三路径
- **单元测试**：AgentLoop 终答检测覆盖无泄漏/泄漏且有sources/泄漏但sources为空三路径
- **集成测试**：用真实 API key 调用硅基流动 rerank 端点（10 候选），验证返回格式正确

### NF4: 兼容性

- **向后兼容**：`rerank.enabled=false`（默认）时系统行为与改动前完全一致
- **依赖零变化**：复用现有 `RestTemplate` / `LLM_API_KEY`，无需新增 Maven 依赖
- **配置迁移**：现有 r01-r07 的环境变量配置无需改动即可重跑

## 五、验收标准

### 单元测试通过

- [ ] `RerankClientTest`：成功/失败/超时三路径 + 响应解析正确性
- [ ] `HybridRetrieverTest`：rerank 开关开启/关闭/失败回退，引用文档 ID 顺序符合预期
- [ ] `AgentLoopTest`：终答泄漏检测命中/不命中/sources 为空，终答内容不含 `<tool_call>` 标记

### r08 评测达标

- [ ] 64 题作答+判分完成，零网络污染（`grep APIConnectionError` = 0）
- [ ] Invalid extra ≤ 1.5（vs r06 的 3.09）
- [ ] Correctness ≥ 40%（vs r06 的 35.94%）
- [ ] 每题引用文档数分布与 r06 类似（说明 rerank 未改变检索结构）

### r07b 评测达标

- [ ] 64 题答案中零题包含 `<tool_call>` 或 `</tool_call>` 字符串
- [ ] Invalid extra ≤ 3.0（vs r07 的 10.27）
- [ ] Correctness ≥ 38%（有效基线，r07 的 32.81% 因泄漏作废）
- [ ] 日志中有 "检测到终答泄漏" 的 WARN 记录（证明降级逻辑触发）

### 代码审查

- [ ] `RerankClient` 遵循 `EmbeddingClient` 的异常处理与重试模式
- [ ] `HybridRetriever` 的 rerank 分支代码 <30 行，无嵌套超过 3 层
- [ ] `AgentLoop` 的终答修复逻辑职责单一（检测 + 解析 + 降级），不改动正常工具循环路径
- [ ] 所有新增配置项在 `application.yaml` 有注释说明用途与默认值

## 六、不做什么（YAGNI）

- **不做 rerank 模型动态切换**：写死用 `BAAI/bge-reranker-v2-m3`，未来需要再加配置
- **不做 rerank 分数阈值过滤**：固定取 top-N，不根据分数绝对值过滤
- **不做文档级聚合**：chunk 级 rerank 即可，不做"同文档多 chunk 按 max 分数聚合"
- **不做 AgentLoop sources 持久化**：不改 `AgentContext` 新增字段，用消息解析即满足需求
- **不做 r08 完整消融矩阵**：只跑 r08（全开+rerank），不跑 r08a/b/c 拆解独立贡献
- **不做 rerank 缓存**：每次检索独立调用，不做查询级去重缓存（评测无重复查询）

---

**Spec 完成。请审批本文档，确认后进入 plan.md 阶段。**

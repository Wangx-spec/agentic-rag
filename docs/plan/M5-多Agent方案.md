# M5 · 多 Agent 方案（主从最简版）

> 前置：M3 完成（AgentLoop + 工具循环）、M2 完成（检索）
> 目标：复合问题由「Leader 拆解 → SubAgent 并行执行 → Aggregator 汇总」的多 Agent 链路完成
> 验收：问「文档里 A 是什么？A 和 B 有什么区别？」→ 拆成 2 个子任务并行执行 → 回答综合两路结论；单子任务失败不影响整体

## 一、范围

**做**
- LeaderAgent：LLM 把复合问题拆解为子问题列表（JSON 输出；解析失败降级为单问题直答）
- SubAgentExecutor：每个子问题独立走「检索 + 子结论生成」（复用 M2 检索与 LLM，不递归 AgentLoop），线程池并行
- Aggregator：原问题 + 全部子结论 → LLM 流式生成最终回答
- MultiAgentOrchestrator：编排三步 + 降级（全部子任务失败回退普通 RAG 链路）
- SSE 过程事件：拆解结果 / 子任务开始 / 子任务完成 / 汇总开始（复用 thinking 通道）
- 触发方式：`mode: multi-agent` 显式指定（自动判定复杂度不在本期）

**不做**
- 不做共识 / 多轮辩论 / 专家互评
- 不做子 Agent 间通信与任务 DAG
- 不做子 Agent 的工具循环（子 Agent = 检索 + 生成，工具循环是 M3 主 Agent 的事）
- 不做复杂度自动路由（后续可选增强）

## 二、核心设计

### 编排流程

```
POST /api/chat {mode: multi-agent, message}
 → MultiAgentOrchestrator.orchestrate(ctx)
    ├─ LeaderAgent.plan(question) 【事件: 拆解完成，N 个子问题】
    │    LLM 输出 JSON: {"subQuestions": ["...", "..."]}（上限 4 个，超出截断）
    │    失败降级：直接按原问题走普通 RAG 链路
    ├─ SubAgentExecutor × N 并行（multiAgentExecutor 线程池）
    │    每个：HybridRetriever.retrieve(子问题) → 子结论（LLM 同步，带该子问题引用片段）
    │    【事件: 子任务 n 开始 / 完成(成功|失败)】
    │    单个失败不阻塞整体（exceptionally → 失败标记，Aggregator 跳过）
    ├─ 全部失败 → 回退普通 RAG 链路（降级）
    └─ Aggregator.aggregate(question, 子结论列表) 【事件: 汇总开始】
         → LLM 流式生成最终回答（delta 通道）+ 合并 sources → done
```

### 类设计（新增 multiagent/ 包）

```
multiagent/
├── MultiAgentOrchestrator.java   — 编排入口，返回 boolean（false=降级）
├── LeaderAgent.java              — 拆解：LLM JSON 输出 + 解析 + 降级
├── SubAgentExecutor.java         — 子任务执行（检索+子结论）
├── Aggregator.java               — 汇总流式生成 + sources 合并
└── dto/SubTaskResult.java        — record(index, question, conclusion, sources, success, error)
```

- `multiAgentExecutor` 线程池：core 2 / max 8 / CallerRuns（与 M1 chatExecutor 隔离，聊天线程不被多 Agent 批量挤占）
- 子结论 prompt：`子问题 + 检索片段 → 一段带 [n] 引用的结论`（非流式，同步 chat）
- 汇总 prompt：`原问题 + 各子结论 → 综合回答，保留 [n] 引用，子结论冲突时如实说明`

### 事件（thinking 通道，前端零改动）

```
[多Agent] 拆解完成：2 个子问题
[多Agent] 子任务 1 开始：A 是什么
[多Agent] 子任务 2 开始：A 和 B 的区别
[多Agent] 子任务 1 完成
[多Agent] 子任务 2 完成
[多Agent] 开始汇总回答
```

## 三、新增/修改文件清单

| 操作 | 文件 | 说明 |
|---|---|---|
| 新建 | multiagent/ 包全部类（5 个） | 编排四件套 + dto |
| 新建 | config/ExecutorConfig.java | multiAgentExecutor 线程池 Bean |
| 修改 | api/ChatController.java | mode=multi-agent 分流 |
| 修改 | static/index.html | 模式选择增加「多 Agent」 |
| 新建 | test：MultiAgentOrchestratorTest | Mock Leader/SubAgent/Aggregator，验证编排/并行/降级/事件序列 |

## 四、任务拆解

| 任务 | 步骤 | 验证 |
|---|---|---|
| T1 Leader 拆解 | prompt + JSON 解析 + 失败降级 + 上限截断 | 单测：正常解析 / 畸形 JSON 降级 / 超上限截断 |
| T2 子任务执行 | 检索 + 子结论生成 + 失败标记（不抛出） | 单测：正常生成 / 检索异常返回失败结果 |
| T3 汇总器 | 子结论合并 prompt + 流式输出 + sources 合并 | 单测：多路 sources 合并去重 |
| T4 编排器 | 并行调度 + 事件推送 + 全失败降级 | 编排单测（Mock 全链）：事件序列、部分失败、全失败回退 |
| T5 接入 | mode 分流 + 前端选项 | 端到端：复合问题 → 事件可见 → 回答综合 |
| T6 回归 | 三种旧模式行为不变 | 全量测试绿 |

## 五、验收标准

- [ ] 复合问题拆成 ≥2 个子任务，事件按「拆解 → 子任务×N → 汇总」顺序推送
- [ ] 子任务并行执行（日志时间戳重叠可证）
- [ ] 回答综合各子结论，引用来源合并展示
- [ ] 单个子任务失败（如检索空）时，回答基于其余子任务结论生成
- [ ] Leader 拆解失败（Mock 畸形输出）时自动走普通 RAG 链路，用户无感
- [ ] 其余模式（plain/rag/agent）回归无变化
- [ ] 全量测试绿

## 六、关键取舍（面试可讲）

1. **子 Agent 不递归 AgentLoop**：子任务是「检索+生成」的固定流程，递归引入不可控轮次与成本；主从职责单一
2. **Leader 失败降级直答而非报错**：拆解是增强不是依赖，原问题本身可走 RAG 链路兜底
3. **线程池隔离**：多 Agent 并行不挤占普通聊天线程（CallerRuns 提供天然背压）
4. **砍共识**：单轮拆解-执行-汇总已能覆盖「复合问题」场景；共识需要分歧判定 prompt 的反复调优，性价比低（ragent 二开方案里 Phase B 的教训）

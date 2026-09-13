# M5 · 多 Agent 方案（主从最简版）

> 前置：M3 完成（AgentLoop + 工具循环）、M2 完成（检索）、M4 完成（auto 意图路由）
> 目标：复合问题由「Leader 拆解 → SubAgent 并行执行 → Aggregator 汇总」的多 Agent 链路完成；auto 模式下意图自动分流，无需用户手动选模式
> 验收：auto 模式问「文档里 A 是什么？A 和 B 有什么区别？」→ 自动进入多 Agent 链路 → 拆成 2 个子任务并行执行 → 回答综合两路结论；单子任务失败不影响整体；单一问题不误入多 Agent

## 一、范围

**做**
- LeaderAgent：LLM 把复合问题拆解为子问题列表（JSON 输出；解析失败降级为单问题直答）
- SubAgentExecutor：每个子问题独立走「检索 + 子结论生成」（复用 M2 检索与 LLM，不递归 AgentLoop），线程池并行
- Aggregator：原问题 + 全部子结论 → LLM 流式生成最终回答
- MultiAgentOrchestrator：编排三步 + 降级（全部子任务失败回退普通 RAG 链路）
- SSE 过程事件：拆解结果 / 子任务开始 / 子任务完成 / 汇总开始（复用 thinking 通道）
- 触发方式：**auto 模式意图分流为主**（意图枚举新增 MULTI_TASK，复合问题自动进入多 Agent 链路，前端不新增模式枚举）；显式 `mode: multi-agent` 保留作评测/演示/手动兜底

**不做**
- 不做共识 / 多轮辩论 / 专家互评
- 不做子 Agent 间通信与任务 DAG
- 不做子 Agent 的工具循环（子 Agent = 检索 + 生成，工具循环是 M3 主 Agent 的事）
- 不做 LLM 自由编排（运行时动态决定流程结构 / 子 Agent 间任务 DAG）——LLM 只在明确的路由决策点选分支，管线结构（拆解→并行→汇总、失败降级）由代码决定

## 二、核心设计

### 触发与路由（auto 模式自然分流，不新增前端枚举）

M4 意图枚举（CHAT/KB_QA/TOOL_TASK/OFF_TOPIC）扩展一类 `MULTI_TASK`，auto 模式下 IntentClassifier 命中 MULTI_TASK → 进入 MultiAgentOrchestrator：

- 分类 prompt 补充 MULTI_TASK 定义与正反例（正：「A 是什么？A 和 B 有什么区别？」；反：单一事实问题 → KB_QA）
- **路由偏好「宁可漏判不可误判」**：多 Agent 链路成本约 3-5 倍（N 次检索 + N 次子结论 + 1 次汇总），prompt 明示「仅当问题明确包含多个独立子问题时才判 MULTI_TASK」，不确定时倾向单链路——判漏的代价只是一次普通 RAG，判错的代价是 3-5 倍成本
- **两道保险**：①置信度低于阈值（复用 M4 `confidence-threshold`）→ 回退隐式路由；②进入编排后 Leader 拆解结果仅 1 个子问题 → 降级为单问题直答（拆解器纠错路由器）
- 显式 `mode: multi-agent` 参数保留：M6 评测跑路由准确率、面试演示强制走链路、auto 分错时手动兜底

### 编排流程

```
POST /api/chat {mode: auto | multi-agent, message}   // auto：MULTI_TASK 意图命中自动进入；multi-agent：显式兜底
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

- `multiAgentExecutor` 线程池：core 2 / max 8 / CallerRuns（与主 ForkJoinPool.commonPool() 隔离，聊天线程不被多 Agent 批量挤占）
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
| 修改 | intent/Intent.java、IntentClassifier.java | 意图枚举 +MULTI_TASK，分类 prompt 补正反例（复合问题判定） |
| 修改 | api/ChatController.java | auto 模式 MULTI_TASK 自动分流 + 显式 mode=multi-agent 兜底 |
| 修改 | static/index.html | 模式选择增加「多 Agent」 |
| 新建 | test：MultiAgentOrchestratorTest | Mock Leader/SubAgent/Aggregator，验证编排/并行/降级/事件序列 |

## 四、任务拆解

| 任务 | 步骤 | 验证 |
|---|---|---|
| T1 Leader 拆解 | prompt + JSON 解析 + 失败降级 + 上限截断 | 单测：正常解析 / 畸形 JSON 降级 / 超上限截断 |
| T2 子任务执行 | 检索 + 子结论生成 + 失败标记（不抛出） | 单测：正常生成 / 检索异常返回失败结果 |
| T3 汇总器 | 子结论合并 prompt + 流式输出 + sources 合并 | 单测：多路 sources 合并去重 |
| T4 编排器 | 并行调度 + 事件推送 + 全失败降级 | 编排单测（Mock 全链）：事件序列、部分失败、全失败回退 |
| T5 显式接入 | mode=multi-agent 分流 + 前端选项 | 端到端：复合问题 → 事件可见 → 回答综合 |
| T6 auto 路由接入 | Intent 枚举 +MULTI_TASK、分类 prompt 正反例、ChatController auto 分流、低置信度回退 | 单测：复合问题判 MULTI_TASK / 单一问题不误判 / 低置信度回退隐式路由；auto 端到端分流 |
| T7 回归 | 旧三模式 + auto 既有行为不变 | 全量测试绿 |

## 五、验收标准

- [ ] auto 模式下复合问题自动进入多 Agent 链路，单一问题不误判（意图评测集抽样 ≥20 条）
- [ ] 复合问题拆成 ≥2 个子任务，事件按「拆解 → 子任务×N → 汇总」顺序推送
- [ ] 子任务并行执行（日志时间戳重叠可证）
- [ ] 回答综合各子结论，引用来源合并展示
- [ ] 单个子任务失败（如检索空）时，回答基于其余子任务结论生成
- [ ] Leader 拆解失败（Mock 畸形输出）时自动走普通 RAG 链路，用户无感
- [ ] 其余模式（plain/rag/agent）回归无变化
- [ ] 全量测试绿

## 六、关键取舍（面试可讲）

1. **子 Agent 不递归 AgentLoop（worker = 轻量管线，非自主 agent）**：子任务是「检索+生成」的固定流程——Leader 已把问题拆到原子，递归 AgentLoop 会多付一轮「LLM 决定要不要调工具」的 token 与延迟，换来零收益，还引入不可控轮次。**演进路径**：SubAgentExecutor 保持 `execute(subQuestion) → SubTaskResult` 单一职责接口；当子任务需要工具迭代时，其内部实现可切换为「构造 scoped AgentContext（子问题提示词 + 工具子集 + 小轮次上限）委派 AgentLoop.run()」，编排层零改动——前提是先泛化 AgentLoop 中 `instanceof SearchKnowledgeBaseTool` 的 sources 收集特判（重构项：让 Tool 声明 sources 语义或经 context listener 收集）
2. **Leader 失败降级直答而非报错**：拆解是增强不是依赖，原问题本身可走 RAG 链路兜底
3. **线程池隔离**：多 Agent 并行不挤占普通聊天线程（CallerRuns 提供天然背压）
4. **砍共识**：单轮拆解-执行-汇总已能覆盖「复合问题」场景；共识需要分歧判定 prompt 的反复调优，性价比低（ragent 二开方案里 Phase B 的教训）
5. **路由器选分支、代码管编排**：auto 分流只把「走哪条管线」交给意图分类器，管线结构（拆解→并行→汇总、失败降级）全部留在代码——对齐业界「workflow 优先、需要灵活性再上 orchestrator-workers」的工程共识（Anthropic《Building Effective Agents》）；LLM 决定的粒度到「拆哪些子问题」为止，不决定流程结构本身。显式 mode 保留 = 评测可控 + 演示可控 + 用户兜底
6. **命名决策：multiagent 是 feature 名，不随 worker 形态改名**：严格说本模式是 Anthropic 定义的 **orchestrator-workers**（编排器-工作者），worker 为轻量执行单元而非自主 agent；学术近亲是 RAG 文献的 query decomposition（decompose-retrieve-synthesize），不是 AutoGen/CrewAI 那类角色对话式多 Agent。但**不改名**：① 模块名描述「多 Agent 协作能力」这一产品特性，且 MultiAgentOrchestrator 类名已含 orchestrator 语义，代码读者不会误解；② `multi-agent` mode 值与简历关键词价值真实；③ 一旦演进路径落地（worker 委派 AgentLoop），multi-agent 之名即字面成立——现在改名等于将来再改回来。配套两条诚实措施：SubAgentExecutor 的 javadoc 明示 worker 语义（「轻量执行器，非自主 agent」）；面试话术为「orchestrator-workers 模式的多 Agent 编排，worker 是轻量执行器，同一 AgentLoop 引擎可参数化升级为完整子 agent」

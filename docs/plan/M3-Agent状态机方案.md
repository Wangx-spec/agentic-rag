# M3 · Agent 状态机方案（思考-行动-观察循环）

> 前置：M2 完成（检索链路可用）
> 目标：自研 Agent 核心引擎——状态机 + function calling 工具循环 + 参数校验 + 过程事件
> 验收：提问「查一下 XX 文档里关于 Y 的说明」，Agent 自动调用知识库检索工具并基于结果回答；SSE 可见工具调用过程

## 一、范围

**做**
- 状态机：`THINKING → ACTING → OBSERVING → FINAL`，最多 5 轮（防死循环）
- OpenAI function calling：模型原生 tool_calls（不走 prompt 模拟 JSON，成功率高）
- 工具接口落地：`Tool` 接口 + `ToolRegistry`（M1 已占位）+ 参数校验器（必填/类型，手写轻量实现）
- 内置工具：`SearchKnowledgeBaseTool`（接 M2 检索）+ `CalculatorTool`（确定性演示工具，供 M6 评测用）
- SSE 过程事件：工具调用/观察结果以 thinking 通道推送
- 聊天接口开关：`/api/chat` 增加 `agent` 参数（true 走 AgentLoop，false 走 M2 RAG 链路）

**不做**
- 不做 prompt-based JSON 工具协议（function calling 已覆盖）
- 不做 Docker 沙箱（工具为受控本地实现）
- 不做工具并行调用（一次一轮一个工具，简化）

## 二、核心设计

### 状态机（AgentState）

```
        ┌────────────────────────────┐
        ▼                            │
THINKING ──(需要工具)──▶ ACTING ──▶ OBSERVING ──┘
   │ 不需要工具 / 达到上限
   ▼
 FINAL（流式输出最终回答）
```

- `AgentLoop.run(AgentContext ctx)`：循环直到 FINAL 或 maxRounds
- `AgentContext`：messages、可用工具、当前轮次、状态轨迹（供事件与调试）
- 每轮调用 `LlmClient.chatWithTools(messages, toolSchemas)`，模型返回：
  - `content`（有内容且无 tool_calls）→ FINAL
  - `tool_calls[{id, name, arguments}]` → ACTING

### LlmClient 扩展

```java
record ToolCall(String id, String name, String argumentsJson) {}
record LlmResponse(String content, List<ToolCall> toolCalls) {}

LlmResponse chatWithTools(List<ChatMessage> messages, List<ToolSchema> tools)
```

- 请求体加 `tools: [{type:"function", function:{name,description,parameters}}]`
- 解析 `choices[0].message.tool_calls[]` 与 `content`
- 工具结果以 `{"role":"tool","tool_call_id":id,"content":...}` 拼回 messages（OpenAI 规范）

### 工具接口与校验（M1 已占位，本期落地）

```java
interface Tool {
    String name();
    String description();
    String parametersSchema();          // JSON Schema 字符串
    String execute(Map<String,Object> arguments);
}
```

- `ToolSchemaValidator`：解析 schema 的 `required` / `properties.type`，校验 arguments：
  - 缺必填 → 不执行，把「缺失参数 xx」作为观察结果反馈模型重试
  - 类型不符 → 同样反馈，不执行
  - 校验失败**不抛异常**——错误信息本身就是模型的下一手信息

### 内置工具

| 工具 | 说明 | 参数 |
|---|---|---|
| `search_knowledge_base` | 调 HybridRetriever，返回 top5 片段（带编号与文档名） | `query`(string, 必填) |
| `calculator` | 四则运算（确定性，评测工具调用成功率的基线） | `expression`(string, 必填) |

### SSE 过程事件（复用 thinking 通道）

AgentLoop 每步推一条：
- `⚙️ 思考：判断需要调用工具 search_knowledge_base`
- `🔧 调用工具：search_knowledge_base({"query":"..."})`
- `👁 观察：检索到 3 条相关片段`
- `✅ 生成最终回答`（随后走原有 delta 流式）

前端零改动（已有 thinking/bubble 通道展示机制）。

## 三、聊天接入

```
POST /api/chat {sessionId, message, agent: true}
  → AgentLoop.run
      ├─ 多轮工具循环（事件推 thinking）
      └─ FINAL：把最终回答按 delta 流式推送（复用 LlmClient.chatStream 或直接分段推送）
  → done 事件（附 sessionId；sources 若经过检索工具则附带）
```

- `agent` 默认 false（M2 RAG 链路），前端加一个开关勾选（可选，M4 做 UI）

## 四、新增/修改文件清单

| 操作 | 文件 | 说明 |
|---|---|---|
| 修改 | llm/LlmClient.java | +chatWithTools / LlmResponse / ToolCall |
| 新建 | llm/dto/ToolCall.java、LlmResponse.java | 响应模型 |
| 新建 | agent/AgentState.java | 状态枚举 |
| 新建 | agent/AgentContext.java | 循环上下文 + 状态轨迹 |
| 新建 | agent/AgentLoop.java | 主循环（替换 M1 占位） |
| 新建 | tool/ToolSchemaValidator.java | 参数校验器 |
| 新建 | tool/tools/SearchKnowledgeBaseTool.java | 接 HybridRetriever |
| 新建 | tool/tools/CalculatorTool.java | 确定性演示工具 |
| 修改 | api/ChatController.java | +agent 参数分流 |
| 新建 | test：AgentLoopTest / ToolSchemaValidatorTest | Mock LLM 验证循环/校验 |

## 五、任务拆解

| 任务 | 步骤 | 验证 |
|---|---|---|
| T1 LlmClient 扩展 | chatWithTools 请求/响应解析（tools 参数 + tool_calls + role:tool 回传） | 单测（Mock HTTP 返回 tool_calls JSON） |
| T2 状态机骨架 | AgentState 枚举 + AgentContext + AgentLoop 主循环（maxRounds 保护） | AgentLoopTest：Mock 两轮回合收敛到 FINAL |
| T3 参数校验器 | required/type 校验，失败转观察结果 | ToolSchemaValidatorTest：缺参/错类型均返回可读提示 |
| T4 内置工具 | SearchKnowledgeBaseTool（Mock retriever）+ CalculatorTool（手写表达式解析，四则+括号） | 单测：各自 execute 正确 |
| T5 过程事件 | AgentLoop 内事件推送接口（Reporter） | 编排测：事件序列顺序断言 |
| T6 聊天接入 | agent 参数分流 + 最终回答流式 | 端到端：问文档问题 → SSE 见工具事件 → 回答带引用 |
| T7 上限与降级 | 达 maxRounds 强制 FINAL；LLM 异常回退普通 RAG 链路 | AgentLoopTest：上限用例；异常用例 |

## 六、验收标准

- [ ] 问「文档里关于 X 的内容」→ SSE 依次出现 思考/调用 search_knowledge_base/观察 事件 → 回答基于检索内容
- [ ] 问「23*47+5 等于多少」→ 调用 calculator 且结果正确
- [ ] 问常识问题（无需工具）→ 不调用工具直接回答（一轮 FINAL）
- [ ] 连续需要两次检索的问题 → 两轮工具循环后回答
- [ ] 模型返回畸形 tool_calls 时，Agent 把错误反馈给模型并收敛，不崩溃
- [ ] 达 5 轮上限强制输出，不死循环
- [ ] `agent=false` 时行为与 M2 完全一致（回归）
- [ ] 全量测试绿

## 七、关键取舍（面试可讲）

1. **function calling 而非 prompt-JSON**：模型原生协议，参数解析成功率显著更高、无需容畸形文本
2. **校验失败不抛异常、转观察结果**：把错误还给模型重试，是 Agent 自愈的标准模式
3. **单工具串行**：先保证循环正确性；并行工具在 M5 多 Agent 层面用 Worker 并行覆盖
4. **maxRounds=5 硬上限**：成本与死循环防护，强制收敛而非放任

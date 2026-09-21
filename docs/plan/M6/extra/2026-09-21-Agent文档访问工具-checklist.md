# Agent 文档访问工具 + r08b 消融 Checklist

> 依据：spec.md 验收标准 ｜ 每项通过运行代码或观察行为验证

## 实现完整性
- [x] get_document 已实现并可被调用（验证：`./mvnw compile -q` 通过，工具类存在于 tool/tools 包）
- [x] list_documents 已实现并可被调用（验证：同上）
- [x] 截断行为正确（验证：单测 truncatesLongBodyAndMarksTotalChars——500 字符截到 50 并标注「完整内容共 500 字符」，对应 AC1）
- [x] 异常输入行为正确（验证：单测——不存在 ID / 缺参数 / 非数字参数均返回明确提示文本，对应 AC2）
- [x] 列表行为正确（验证：单测——默认 limit、显式 limit、超上限截断、非 DONE 过滤、空库提示，对应 AC3）

## 集成
- [x] 两个工具经 ToolRegistry 自注册（验证：单测断言 register 被调用；r08b 启动日志出现「已注册工具: get_document」「已注册工具: list_documents」，对应 AC4）
- [x] AGENT 模式可用工具列表包含两个新工具（验证：r08b 启动日志注册 4 工具：calculator / get_document / list_documents / search_knowledge_base，对应 AC4）
- [x] 不改动 AgentLoop / ChatService（验证：commit 2cf9ada 文件清单中两文件无变化）

## 编译与测试
- [x] 项目编译无错误（`./mvnw compile -q`）
- [x] 所有单元测试通过（`./mvnw test` 135 全绿，无既有测试回归；含后续注册开关新增 2 例）

## 端到端场景
- [x] 场景 1：r08b 作答跑完 64 题（验证：r08b.jsonl 行数=64，run 日志出现「EnterpriseRAG 评测完成」）
- [x] 场景 2：fail-open 生效（验证：answers 零 `<tool_call>` 泄漏；46 次泄漏检测全部走 sanitize 兜底，进程无中断，对应 AC5）
- [x] 场景 3：判分完成且四指标归档（验证：results-erag-rounds-era2-r08b.json 生成，64/64 题判分完整，对应 AC6）

## r08b × r07d 对比（2026-09-22 实测回填）

| 指标 | r07d | r08b | Δ | 解读 |
|------|------|------|---|------|
| Document Recall | 68.28 | 70.94 | +2.66 | 小幅上升 |
| Completeness | 53.29 | 47.75 | -5.54 | 下降 |
| Correctness | 43.75 | 32.81 | **-10.94** | 明显下降 |
| Invalid Extra | 3.27 | 3.39 | +0.12 | 基本持平 |
| 满分题数 | 28 | 21 | **-7** | 净翻转 10 对→错 / 3 错→对 |
| Combined corr*comp | 39.84 | 29.82 | -10.02 | 下降 |
| 泄漏检测触发 | 35 | 46 | +11 | 均在兜底内，answers 零泄漏 |

**归因分析：**
- 泄漏兜底不是翻转主因：对→错 10 题中 7 题触发泄漏（70%），与全体泄漏率 72%（46/64）基本持平，无显著相关性。
- 翻转题判分理由多为「答案变得保守/声称信息不可得」（qst_0022、qst_0031）或与陷阱文档冲突（qst_0021），符合「工具变多→5 轮 ReAct 预算被 list/get 分散+长文本挤占上下文→有效作答信息变少」的机制。
- 未做重复运行，单次净效应 -7 题超出常规采样波动的可能性较大，结论按单次结果判定。

**结论：两工具对 benchmark 净贡献为负，不进入定版评测配置。**
处置：代码保留（面试叙事价值——ReAct 工具箱从 2 个检索类扩到 4 个工具的工程设计），新增 `rag.agent.document-tools-enabled` 注册开关（默认 true 供 demo 叙事；定版评测复测时设 `RAG_AGENT_DOCUMENT_TOOLS_ENABLED=false` 回退 r07d 口径）。准确率轨的下一步增量回到查询理解前置与 rerank 阈值过滤两案。

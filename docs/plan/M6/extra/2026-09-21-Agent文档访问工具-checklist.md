# Agent 文档访问工具 + r08b 消融 Checklist

> 依据：spec.md 验收标准 ｜ 每项通过运行代码或观察行为验证

## 实现完整性
- [ ] get_document 已实现并可被调用（验证：`./mvnw compile -q` 通过，工具类存在于 tool/tools 包）
- [ ] list_documents 已实现并可被调用（验证：同上）
- [ ] 截断行为正确（验证：单测用例——超长正文被截断且结果含「已截断」与完整总字符数，对应 AC1）
- [ ] 异常输入行为正确（验证：单测用例——不存在 ID / 缺参数 / 非数字参数均返回明确提示文本，对应 AC2）
- [ ] 列表行为正确（验证：单测用例——默认 limit、显式 limit、超上限截断、非 DONE 过滤、空库提示，对应 AC3）

## 集成
- [ ] 两个工具经 ToolRegistry 自注册（验证：单测断言 register 被调用；应用启动日志出现「已注册工具: get_document」「已注册工具: list_documents」，对应 AC4）
- [ ] AGENT 模式可用工具列表包含两个新工具（验证：r08b 作答日志中 LLM 可见工具 schema 数 = 原工具数 + 2，或日志出现对这两个工具的 tool_call，对应 AC4）
- [ ] 不改动 AgentLoop / ChatService（验证：`git diff HEAD~1 --stat` 中两文件无变化）

## 编译与测试
- [ ] 项目编译无错误（`./mvnw compile -q`）
- [ ] 所有单元测试通过（`./mvnw test -q` 全绿，无既有测试回归）

## 端到端场景
- [ ] 场景 1：r08b 作答跑完 64 题（验证：eval-answers/rounds-era2/r08b.jsonl 行数=64，run 日志出现「EnterpriseRAG 评测完成」）
- [ ] 场景 2：fail-open 生效（验证：r08b 作答日志中无未捕获异常堆栈导致的中断；若有工具错误均以文本返回并继续，对应 AC5）
- [ ] 场景 3：判分完成且四指标归档（验证：results-erag-rounds-era2-r08b.json 生成，64 题判分完整，对应 AC6）

## r08b × r07d 对比（作答+判分完成后回填）

基线 r07d：Recall 68.28 / Completeness （以 r07d results 为准）/ Correctness 43.75 / Invalid Extra 3.27 / 满分 28 题

| 指标 | r07d | r08b | Δ | 解读 |
|------|------|------|---|------|
| Document Recall | 68.28 | （待回填） | | |
| Completeness | （待回填） | （待回填） | | |
| Correctness | 43.75 | （待回填） | | |
| Invalid Extra | 3.27 | （待回填） | | |
| 满分题数 | 28 | （待回填） | | |

结论：（待回填——两工具净贡献为正/中性/负，是否固化为默认配置）

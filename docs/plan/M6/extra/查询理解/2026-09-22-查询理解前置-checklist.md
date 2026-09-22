# 查询理解前置 — checklist（验收清单）

> 对应 task：`2026-09-22-查询理解前置-task.md`
> 日期：2026-09-22

## 实现完整性
- [x] QueryUnderstanding record 存在且四字段齐全（T1；`./mvnw compile -q` 通过）
- [x] QueryUnderstandingService 实现完整流程：开关→LLM→容错解析→校验→Optional（T2）
- [x] 提示词五类意图定义与 IntentClassifier 口径一致（对照两文件示例一致）
- [x] 配置接入：`RagProperties.Agent.queryUnderstandingEnabled` + yaml `rag.agent.query-understanding-enabled`，缩进正确（T3）
- [x] ChatService.runAgent 接线：引导消息注入 + routedIntent 填意图（T4；兼容旧构造器）

## 故障开放（对应 AC3/AC4）
- [x] 非法 JSON → empty（T5 用例 3）
- [x] 未知意图 → empty（T5 用例 4）
- [x] LLM 异常 → empty（T5 用例 5）
- [x] 开关关闭 → empty 且 LlmClient 零调用（T5 用例 6）
- [x] 低置信度（< IntentProperties.confidenceThreshold）→ empty

## 编译与测试
- [x] 单测六类路径全绿（T5：`./mvnw test -Dtest=QueryUnderstandingServiceTest`，实际覆盖 7 路）
- [x] 全量测试无回归（T6：`./mvnw test`，143/143）

## r09 原题消融（对应 AC5 防回退）
- [ ] 作答完整：`r09.jsonl` 行数 = 64，run 日志出现「EnterpriseRAG 评测完成」（待外部评测）
- [ ] 查询理解生效：run 日志可见查询理解调用（意图 + 规范查询）（待外部评测）
- [ ] answers 零泄漏：无 `<tool_call>` 残留（待外部评测）
- [ ] 判分完成：results json 64 题判分完整（待外部评测）

## r09v1 变体消融（对应 AC5 提升验证）
- [ ] 作答完整：`r09v1.jsonl` 行数 = 64（变体题库 62/64 英文，待外部评测）
- [ ] 判分完成（待外部评测）

## 对比结论（判分后回填）

基线：r07d 原题 recall 68.28 / corr 43.75 / 满分 28 题；v1 基线 recall 40.23（差距 -28.1pp）

| 指标 | r07d（原题基线） | r09（原题） | Δ | r09v1（变体） | 对 r09 差距 |
|------|------|------|---|------|------|
| Document Recall | 68.28 | （待回填） | | （待回填） | 目标 <15pp |
| Completeness | 53.29 | （待回填） | | （待回填） | |
| Correctness | 43.75 | （待回填） | 红线 ±3pp | （待回填） | |
| Invalid Extra | 3.27 | （待回填） | | （待回填） | |
| 满分题数 | 28 | （待回填） | | （待回填） | |

结论：（待回填——原题是否回退 / v1 是否达标 / 开关固化策略：默认开启 or 默认关闭）

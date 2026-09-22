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
- [x] 作答完整：`r09.jsonl` 行数 = 64（用户自行作答，2026-09-22 19:28 产物）
- [x] answers 零泄漏：无 `<tool_call>` 残留（grep 计数 0）
- [x] 判分完成：results json 64 题判分完整

## r09v1 变体消融（对应 AC5 提升验证）
- [x] 作答完整：`r09v1.jsonl` 行数 = 64（题库 `data/derived/questions-variants-dejargon.jsonl`，64 题英文）
- [x] 查询理解生效：run 日志 64 题全部触发「查询理解完成」（如 normalizedQuery=certification validity）
- [x] answers 零泄漏
- [x] 判分完成

## 对比结论（已回填）

基线：r07d 原题 recall 68.28 / corr 43.75 / 满分 28 题；v1 基线 recall 40.23（差距 -28.1pp）

| 指标 | r07d（原题基线） | r09（原题） | Δ | r09v1（变体） | 对 r09 差距 |
|------|------|------|---|------|------|
| Document Recall | 68.28 | 70.92 | +2.64 | 46.68 | **-24.2pp（目标 <15pp，未达）** |
| Completeness | 53.29 | 54.40 | +1.11 | 44.72 | -9.68pp |
| Correctness | 43.75 | 50.00 | **+6.25**（超红线正向） | 35.94 | |
| Invalid Extra | 3.27 | 3.12 | -0.15 | 3.69 | |
| 满分题数 | 28 | 32 | +4（10 错→对 / 6 对→错） | 23 | |

v1 前后对比：recall 40.23 → 46.68（**+6.45pp**），对原题差距 -28.1pp → -24.2pp。

结论：
- **原题防回退：达标且超预期**——四指标全部向好，corr +6.25pp 领涨（与 r08b「recall 升 corr 崩」的模式相反，说明规范查询改善的是作答质量而非只改善检索）。
- **v1 提升验证：有改善但未达 <15pp 目标**——v1 recall +6.45pp，差距从 -28.1pp 收窄到 -24.2pp。
- **开关固化策略：默认开启（固化）**。双指标均为正贡献（原题 corr +6.25、v1 recall +6.45），无任一回退信号；v1 剩余差距记录为遗留短板，候选后续手段：规范查询多扩展（一次产出 2-3 条候选查询）、引导文案强化「必须用规范查询」、或 rerank 阈值过滤。
- 遗留：r09 作答由用户自行运行、无 run 日志，文档工具开关关闭状态未能从日志复核（r09v1 轮已复核口径无误）。

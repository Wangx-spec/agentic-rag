# Reranker 接入与 Agent 终答修复 Checklist

> 基于已批准的 spec.md、plan.md、task.md，定义可观测的验收条件。
> 每项通过运行代码或观察行为来验证，不依赖逐行阅读代码。

---

## 一、实现完整性

### 1.1 新增文件就绪

- [ ] `src/main/java/com/agenticrag/rag/retrieve/RerankResult.java` 存在且为 `record` 类型
- [ ] `src/main/java/com/agenticrag/rag/retrieve/RerankClient.java` 存在，包含 `rerank(String, List<String>, int, String)` 方法
- [ ] `src/test/java/com/agenticrag/rag/retrieve/RerankClientTest.java` 存在
- [ ] `src/test/java/com/agenticrag/agent/AgentLoopTest.java` 存在

### 1.2 配置项就绪

- [ ] `application.yaml` 包含 `rag.retrieval.rerank.enabled` 配置项
- [ ] `application.yaml` 包含 `rag.retrieval.rerank.model` 配置项
- [ ] `application.yaml` 包含 `rag.retrieval.rerank.candidate-size` 配置项
- [ ] `application.yaml` 包含 `rag.retrieval.rerank.final-top-n` 配置项
- [ ] `scripts/run-erag-round.sh` 支持第 5 个参数透传 `RAG_RETRIEVAL_RERANK_ENABLED`

---

## 二、编译与测试

- [ ] `./mvnw clean compile` 成功，零编译错误
- [ ] `./mvnw test -Dtest=RerankClientTest` 全绿（3 个测试用例：成功/网络异常/4xx）
- [ ] `./mvnw test -Dtest=HybridRetrieverTest` 全绿（含 3 个新用例：rerank 开/关/回退）
- [ ] `./mvnw test -Dtest=AgentLoopTest` 全绿（3 个测试用例：无泄漏/泄漏有 sources/泄漏无 sources）
- [ ] `./mvnw test` 全绿（全量测试无回归）

---

## 三、行为验证（RAG 模式 rerank 开启）

用任意一个问题启动应用（`RAG_RETRIEVAL_RERANK_ENABLED=1`），观察日志：

- [ ] 日志出现 `Rerank 完成：N 候选 → top M` 字样（说明 rerank 调用成功）
- [ ] 日志 **不出现** `Rerank 调用失败` 字样（说明无 fail-open 降级）
- [ ] 关闭 rerank（`RAG_RETRIEVAL_RERANK_ENABLED=0`），日志 **不出现** 任何 Rerank 字样（说明开关有效）
- [ ] 关闭 rerank 时应用行为与原 r06 完全一致（向后兼容）

---

## 四、行为验证（AgentLoop 终答泄漏修复）

单元测试已覆盖三路径，额外手工验证：

- [ ] 启动 AGENT 模式对话，随机输入一个知识库查询
  - 答案内容**不含** `<tool_call` 或 `</tool_call>` 字符串
- [ ] 查看日志：若降级触发，日志包含 `检测到终答泄漏（含 <tool_call> 标记），触发 RAG 合成降级`
- [ ] 泄漏 sources 为空的边界（单元测试已覆盖）：返回原始终答而非崩溃

---

## 五、评测验证 r08（RAG 模式 + rerank，方案 B 完整消融矩阵）

### 5.1 作答质量

- [ ] `eval-answers/rounds-era2/r08.jsonl` 包含恰好 64 行（64 题全部作答）
- [ ] 扫描答案：`grep -c '<tool_call' eval-answers/rounds-era2/r08.jsonl` 输出 **0**
- [ ] r08 作答过程日志：`grep -c FAILED eval-answers/rounds-era2/r08-run.log` 输出 **0**

### 5.2 判分质量

- [ ] 判分日志末尾：`Questions scored: 64`、`Skipped rows: 0`
- [ ] 判分污染检查：`grep -c APIConnectionError eval-answers/rounds-era2/r08-judge.log` 输出 **0**（"APIConnectionError=0" 的那行除外）

### 5.3 关键指标达标

- [ ] `Avg invalid extra` ≤ **1.5**（基线 r06 为 3.09，说明 rerank 过滤了冲突陷阱文档）
- [ ] `Avg correctness` ≥ **40%**（基线 r06 为 35.94%，说明生成质量提升）
- [ ] `Avg recall` 不低于 r06 的 66.20% − 5pp（rerank 不应显著降低召回）

---

## 六、评测验证 r07b（AGENT 模式 + rerank + 终答修复）

### 6.1 零泄漏验证（最关键）

- [ ] `grep -c '<tool_call' eval-answers/rounds-era2/r07b.jsonl` 输出 **0**（对比 r07 为 41）
- [ ] 日志存在至少一条 `检测到终答泄漏` WARN 记录（证明降级路径在评测中触发）

### 6.2 作答与判分完整性

- [ ] `eval-answers/rounds-era2/r07b.jsonl` 包含恰好 64 行
- [ ] 判分日志：`Questions scored: 64`、`Skipped rows: 0`
- [ ] 判分污染：`grep -c APIConnectionError r07b-judge.log` 输出 **0**

### 6.3 关键指标达标

- [ ] `Avg invalid extra` ≤ **3.0**（r07 为 10.27，rerank 控制了多轮检索噪声）
- [ ] `Avg correctness` ≥ **38%**（r07 的 32.81% 因 41 题泄漏作废，此为新有效基线）
- [ ] `Avg recall` 不低于 r06 的 75.22% − 5pp（多轮检索优势应保持）

---

## 七、代码审查门槛

- [ ] `RerankClient.java` 代码行数 ≤ 150 行（精简实现，无冗余）
- [ ] `HybridRetriever.java` 中新增的 rerank 分支（`applyRerank` + 调用逻辑）≤ 30 行
- [ ] `AgentLoop.java` 中新增的终答修复逻辑（`sanitizeFinalAnswer` + 辅助方法）≤ 80 行
- [ ] 所有新增日志遵循规范：不输出 API Key、query 完整文本截断到 50 字符以内

---

## 八、文档完整性

- [ ] `README.md` 评测表格已填入 r08/r07b 真实成绩（不含占位符 X.XX）
- [ ] `docs/plan/M6/2026-09-16-M6性能优化方案-BM25修复与泛化性建设.md` 包含 Phase 7 段落（含实测结果）
- [ ] `docs/plan/M6/` 目录包含四份文档：spec / plan / task / checklist

---

## 九、端到端场景

### 场景 1：RAG 模式，噪声过滤效果验证

1. 用 r06 基准配置（无 rerank）查询一道 r06 曾答错（含数字冲突）的题（如 qst_0021）
2. 再用 r08 配置（+ rerank）查询同一题
3. **预期：** r08 答案中数字与 gold 一致（"3 business days" 而非 "48 hours"）
4. **观察：** 日志中 r06 题答案的 invalid extra 明显高于 r08

### 场景 2：AGENT 模式，终答不含工具调用标记

1. 启动 AGENT 模式，输入 benchmark 中一道已知会触发泄漏的题（如 qst_0023）
2. **预期：** 终答内容是关于"合并前审阅角色"的正常中文回答，不含 `<tool_call>`
3. **观察：** 日志出现 `检测到终答泄漏` → `RAG 合成降级` 的完整流程

---

**checklist.md 完成，等待批准后按 task.md 顺序开始实施（T1 → T2 → … → T9）。**

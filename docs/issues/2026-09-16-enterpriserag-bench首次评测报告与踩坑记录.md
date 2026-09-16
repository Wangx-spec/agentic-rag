# EnterpriseRAG-Bench 首次评测报告与踩坑记录

> 日期：2026-09-16
> 范围：agentic-rag 项目 M6（工程化与评测）— EnterpriseRAG-Bench 真实栈评测首次完整跑通
> 评测形态：真实栈模式（PG `agentic_rag_eval` 独立库 + Qdrant 独立 collection + 真实 Qwen3-Embedding-8B + GLM-5.3 生成）

---

## 一、首次评测报告（检索环节 · 文档级 Recall）

### 1.1 总览

| 指标 | 数值 |
|---|---|
| 语料文档数 | 5,186（Confluence 源切片） |
| 题目总数 / 已作答 | 64 / 64 |
| 文档级 Recall（宏平均） | **48.4%** |
| 产出文件 | `enterpriserag-report.md`、`eval-answers/answers.jsonl`（64 行） |

> 含义：答案藏在某篇文档里，系统检索时**有没有把这篇文档翻出来给 LLM**。48.4% 表示平均只翻到不到一半的"标准答案文档"。这是检索环节指标，不代表答案正确性（正确性需 Phase 4 LLM judge 判分）。

### 1.2 按题型拆解

| 题型 | 题数 | 召回 | 解读 |
|---|---|---:|---|
| constrained | 11 | **81.8%** | ✅ 带明确约束（时间/数值/限定词），检索好定位 |
| intra_document_reasoning | 2 | 100% | ✅ 单文档推理，翻到那一篇即中 |
| miscellaneous | 3 | 100% | ✅ |
| basic | 19 | 47.4% | 中等，事实型问答约一半能翻到 |
| completeness | 11 | 26.9% | ⚠️ 答案分散在 5–9 篇文档，top-K 有限不可能全召回（**结构性上限，非 bug**） |
| semantic | 15 | **26.7%** | ⚠️ 题目与文档**故意无共同关键词**，纯靠语义向量——RAG 最硬的骨头 |
| project_related | 2 | 25% | ⚠️ 需跨多篇文档 |
| conflicting_info | 1 | 50% | 样本太少，参考意义弱 |

### 1.3 结论与面试话术

这是**合理的第一版基线**，价值在于数据驱动地暴露了短板：

> "我给 RAG 系统建了离线评测基线（EnterpriseRAG-Bench 真实栈，5,186 文档语料），文档召回 48.4%。按题型拆解发现 **semantic（无语义重叠词）与 completeness（多文档聚合）是检索短板**。下一步优化：query 改写（HyDE / 多查询扩展）提升语义召回；多文档题型调高 top-K 并加重排序（rerank）。"

---

## 二、踩坑记录（现象 → 根因 → 解决 → 预防）

按评测阶段分类。

### Phase 1 · 数据准备

#### 坑 1：转换脚本内存峰值被 SIGKILL
- **现象**：`convert_enterpriserag.py` 解析 `uuid_index.json`（58MB、51 万条目）时进程被系统杀掉（SIGKILL）。
- **根因**：一次性把大 JSON 全量加载进内存，峰值超过 MacBook 可用内存。
- **解决**：加**断点续跑**（输出文件已存在则跳过），分批跑完。
- **预防**：凡是处理大体量数据，一律先设计断点续跑 / 流式处理，不要一次性全量入内存。

#### 坑 2：后台任务 exit code 2 虚惊
- **现象**：转换后台任务退出码为 2，看似失败。
- **根因**：是 2 篇文档被**内容过滤拦截**的标记，并非任务失败。
- **解决**：核对这 2 篇不在任何题目的 gold 文档里，确认不影响 64 题评测。
- **预防**：exit code 非 0 不等于失败，先看具体语义再下结论。

### Phase 2 · 代码接入

#### 坑 3：题目字段映射 bug（核心 bug）
- **现象**：答题日志 `评测进度 1/64: null (null)`，`answers.jsonl` 里 `question_id` 全是 `null`。
- **根因**：`EnterpriseRagCase` record 字段是 camelCase（`questionId`/`expectedDocIds`），题目 jsonl 是 snake_case（`question_id`/`expected_doc_ids`），**Jackson 默认不做 snake→camel 自动映射**，反序列化为 null。只有 `question` 字段同名幸免（所以答案内容其实是正常的）。
- **解决**：record 四个字段显式加 `@JsonProperty("question_id")` 等注解；并用真实题目行做**运行时反序列化验证**确认映射成功。
- **预防**：JSON 与 Java 字段命名风格不一致时，**一律显式 `@JsonProperty`**，绝不依赖默认映射。

#### 坑 4：时间 / 成本估算严重偏差
- **现象**：预估 embedding 入库 10–25 分钟，实际 **3–5 小时**；成本从"几块钱"上调到"十几元"。
- **根因**：严重低估文档长度——实测 chunk 总数约 **7.4 万**、embedding 约 **37M token**（原估 2.6 万 chunks、13M token）。
- **解决**：修正估算；语料已入库后重跑会幂等跳过，只跑答题+判分约 30–40 分钟。
- **预防**：估算前先跑小样本实测（如先导入 100 篇，看 chunk 数 / 单篇耗时 / token 量），再外推全量。

### Phase 3 · 运行

#### 坑 5：IDE 后台编译干扰（重要，会复现）
- **症状 1**：`spring-boot:run` 启动报 `Unresolved compilation problem`（`EnterpriseRagRunner.java:56` 的 `@RequiredArgsConstructor` 处），bean 创建失败。
- **症状 2**：maven 重编译又报 `找不到符号 JsonProperty`——磁盘源码里 `import JsonProperty` 被 IDE 自动删了（注解却还在）。
- **根因**：IDE（Trae / VS Code Java 扩展）在后台：①用 **ECJ 编译器**自动编译到 `target/classes`，ECJ 没正确编译 record（`questionId()` 等访问器没生成），**覆盖了 maven 正确编译的 class**；②"保存时整理 import（Organize Imports）"把 `@JsonProperty` 的 import 误判为"未使用"删掉。
- **解决**：**只删 `target/classes`**（⚠️ 绝不能 `mvn clean`——会连带删掉 `target/erag-real-data/bm25.db` 业务数据）+ 加回 import + `./mvnw compile`。验证：`javap` 看 Case 有 `questionId()`、Runner 无 `Unresolved` 字符串。
- **预防**：跑 maven 前三选一——关闭 IDE 自动构建（`java.autobuild.enabled=false`）／关闭保存时优化 import（去掉 `editor.codeActionsOnSave` 的 `source.organizeImports`）／跑评测时直接关闭 IDE，用终端独占编译。

#### 坑 6：HikariCP `clock leap` WARN（虚惊）
- **现象**：日志 `Thread starvation or clock leap detected (housekeeper delta=12m36s)`。
- **根因**：MacBook 合盖睡眠后唤醒，系统时钟跳跃，连接池巡检线程检测到两次心跳间隔异常。
- **解决**：**无害，忽略**。连接池自动恢复，不影响已入库数据。
- **预防**：长耗时任务在会睡眠的笔记本上跑，唤醒后见此 WARN 属正常，不必紧张。

#### 坑 7：评测环境与主环境数据隔离（设计决策）
- **背景**：主库 `agentic_rag` 已有 2 篇用户文档、`app` 容器占用 8081 端口、Qdrant 的 point id 与 PG chunk id 关联。
- **解决**：三个隔离点缺一不可——独立库 `agentic_rag_eval` ／ `collection-prefix=erag_eval_chunks` ／ `RAG_DATA_DIR=./target/erag-real-data`；并用 `--spring.main.web-application-type=none` 不起 web server 避开 8081。
- **预防**：评测环境与主环境**必须做数据空间隔离**，否则互相污染。

#### 坑 8：建库脚本相对路径找不到（小坑）
- **现象**：在 `medboard` 目录跑 `cat src/main/resources/db/init.sql`，报 `No such file or directory`。
- **根因**：相对路径依赖当前工作目录，跑错了目录。
- **解决**：用绝对路径；且该步其实可省——`spring.sql.init.mode=always` 启动时会自动建表（init.sql 全 `IF NOT EXISTS`，幂等）。
- **预防**：跨项目操作时用绝对路径；优先依赖应用自身的幂等初始化。

### Phase 4 · 官方判分（前置注意）

#### 坑 9：判分 judge 模型限制
- **现状**：`metrics_based_eval.py` 用 **OpenAI Responses API**，`OpenAI(api_key=...)` 无 `base_url`，仅原生支持 OpenAI / Anthropic。默认模型 `gpt-5.4` / `gpt-5-mini`。
- **影响**：想改用硅基流动 GLM 做 judge，需把 Responses API 改成 Chat Completions 并加 `base_url`（约 20+ 行，改动较大）。脚本默认模型名在普通 OpenAI 账号里可能不存在，需替换成可用模型（如 `gpt-4o-mini`）。
- **建议**：首选 OpenAI key（64 题成本极低）；无 OpenAI key 时再改脚本接 GLM。

---

## 三、经验教训（可复用）

1. **JSON 映射**：snake_case 数据 + camelCase Java 字段，必须显式 `@JsonProperty`，并做运行时反序列化验证，不要只看编译通过。
2. **估算先实测**：大批量任务的耗时/成本，先跑小样本实测再外推，别凭感觉。
3. **IDE 与构建产物隔离**：IDE 后台编译（尤其 ECJ）会污染 `target/classes`。跑 maven 前关掉 IDE 自动构建，或关 IDE。清理时**只删 `target/classes`，绝不 clean**（保护业务数据目录）。
4. **断点续跑是底线**：数据转换、语料入库这类长任务，必须幂等 + 断点续跑。
5. **评测环境隔离**：独立库 / 独立 collection / 独立数据目录三件套，避免污染主环境。
6. **WARN 先辨虚实**：`clock leap`、exit code 非 0 等信号，先查语义再决定是否处理。

---

## 四、复现 / 重跑速查

```bash
# 真实栈评测（语料已入库则幂等跳过，直接答题）
cd /Users/miaobao/agent-projects/agentic-rag
EVAL_BENCH=enterpriserag \
LLM_API_KEY=<硅基流动key> \
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/agentic_rag_eval \
SPRING_DATASOURCE_USERNAME=postgres \
SPRING_DATASOURCE_PASSWORD=postgres \
RAG_DATA_DIR=./target/erag-real-data \
RAG_VECTOR_QDRANT_COLLECTION_PREFIX=erag_eval_chunks \
RAG_MEMORY_TYPE=memory \
./mvnw spring-boot:run \
  -Dspring-boot.run.arguments="--spring.main.web-application-type=none"
```

前置检查：`docker compose` 基础设施（postgres/qdrant/redis）在跑；**IDE 自动构建已关或 IDE 已关**。

---

## 五、追加：判分脚本硅基流动适配（2026-09-16）

**背景**：用户无 OpenAI key，只有硅基流动 key；官方判分脚本 `OpenAILLM` 走 Responses API，硅基流动等 OpenAI 兼容端点不支持。

**方案**（已实施并冒烟验证）：
- 新增 `EnterpriseRAG-Bench-main/src/llm/openai_compatible_llm.py`：走 Chat Completions API，`base_url` 默认 `https://api.siliconflow.cn/v1`，剥离 `<think>...</think>` 块防污染 JSON 解析
- `src/llm/factory.py` 注册 `openai_compatible`（别名 `siliconflow`）provider，`get_llm`/`get_cheap_llm` 均支持
- 判分环境：仓库内 `.venv`（openai 3.14.1 + pydantic，braintrust 惰性导入不装无碍）
- 冒烟验证：GLM-5.3 真实调用 judge 式 JSON 输出 → `extract_json_from_response` 解析 ✅

**教训**：判分链路只依赖 `generate()` 产纯文本 + JSON 提取，不做 tool call，所以兼容端点适配面很小（单文件 provider + 工厂注册两处）。

Phase 4 完整命令见 Runbook `M6-EnterpriseRAG-Bench接入Runbook.md` Phase 4 小节。

---

## 六、追加：判分全 0 事故——环境变量缺失 + 静默重试（2026-09-16）

**现象**：Phase 4 首跑结果 correctness=0.0% / completeness=0.0%（64 题全 False），但 recall=48.37% 正常；每题伴随 `citation stripping failed` 警告；results json 中 `correctness_reasoning` 全部为空串。

**根因**：判分命令在未设置 `LLM_PROVIDER/LLM_API_KEY` 等环境变量的 shell（base 环境）中执行，judge LLM 调用全部初始化失败。而判分脚本所有 LLM 调用点都是 `except Exception: continue` 静默重试 3 次后记 0/False——**配置错误被吞掉，表现为"系统全错"的假象**。

**验证**：用正确环境变量单题重跑 qst_0011 → correct=True / completeness=100% / recall=100%，与原 0 分形成铁证对照。

**修复**（已打进 EnterpriseRAG-Bench-main）：
1. `metrics_based_eval.py` 新增启动预检（preflight）：评测前真实调一次 judge LLM，失败即退出并打印原因，exit code 2
2. wholistic 判分重试循环打印每次异常（类名+消息）
3. citation stripping 失败警告带上异常详情

**教训**：凡"重试后兜底为默认值"的评测代码，必须有启动预检或在最终失败时留下可观测日志；否则环境配置错误会被误读为被测系统的真实低分。跑判分前务必确认四个变量：`LLM_PROVIDER=openai_compatible`、`LLM_API_KEY`、`LLM_BASE_URL`、`LLM_MODEL_NAME`。

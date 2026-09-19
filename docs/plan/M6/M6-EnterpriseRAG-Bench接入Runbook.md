# M6 · EnterpriseRAG-Bench 接入 Runbook（数据准备 → 运行 → 出分）

> 目标：用 EnterpriseRAG-Bench（Onyx，MIT）的 Confluence 子集对 agentic-rag 做**可对外对话的标准分评测**
> 前置文档：[M6-工程化与评测方案.md](M6-工程化与评测方案.md) 的「接入操作手册」小节（勘察结论与切片依据）
> 现状（2026-09-15）：Phase 0/1/2 已完成，Phase 3-5 为运行步骤

---

## 总览

```
Phase 0 前置检查 ✅ → Phase 1 数据准备 ✅ → Phase 2 代码接入 ✅
→ Phase 3 入库+评测(约1小时) → Phase 4 官方判分(约1小时) → Phase 5 报告落盘
```

| 阶段 | 内容 | 状态 |
|---|---|---|
| 0 | 环境/成本/英文能力确认 | ✅ embedding 已确认 Qwen3-Embedding-8B（双语） |
| 1 | 语料转换 + 题目集入仓 | ✅ 本文 Phase 1 记录 |
| 2 | EnterpriseRagRunner 接入 | ✅ 2026-09-15 完成，见设计 |
| 3 | 一条命令跑入库+评测 | ⬜ 约 1 小时（含 embedding 13M token） |
| 4 | 官方 metrics_based_eval 判分 | ⬜ 约 1 小时 |
| 5 | 报告 + README 数字 + 消融 | ⬜ 半天 |

---

## Phase 0 · 前置检查（已完成，留档备查）

| 项 | 结论 |
|---|---|
| embedding 英文能力 | ✅ `llm.embedding-model: Qwen/Qwen3-Embedding-8B`（SiliconFlow），中英双语 |
| 维度 | 4096（`rag.embedding-dim`）——eval profile 用内存向量库动态建集合，无维度迁移问题；若走 qdrant 模式需 collection 维度对齐 |
| 成本预估 | 入库 ~13M embedding token（5,190 篇 × ~2.5K）+ 64 次 chat 调用 + 判分 ~300 次 judge 调用（若用 OpenAI judge）——总成本个位数人民币量级 |
| 磁盘 | `data/enterpriserag-md/` ~52MB（已 gitignore） |

---

## Phase 1 · 数据准备（✅ 2026-09-15 完成）

### 1.1 语料转换（S1）

转换脚本：`scripts/convert_enterpriserag.py`（支持断点续跑，跳过已转换文件）

```bash
# 已执行（再次执行会增量补齐）：
python3 scripts/convert_enterpriserag.py \
  --bench /Users/miaobao/agent-projects/EnterpriseRAG-Bench-main \
  --source confluence \
  --out ./data/enterpriserag-md
```

产物：
- `data/enterpriserag-md/{dsid}.md` × **5,186 篇**（目标 5,190：1 篇无 dsid 跳过 + 2 篇被本机内容过滤拦截 + 1 篇非语料文件）—— **文件名=dsid**（判分对齐的关键），title→`#`、节标题→`##`、space/labels→引用块、summary→段首
- `data/enterpriserag-md/manifest.json` —— dsid→title 映射（调试/报告用）

**完整性已验证（2026-09-15）**：64 题的全部 expected_doc_ids 均有对应 md 文件（`gold 文档缺失检查：全部命中`）；被拦截的 2 篇不在任何题目的 gold 里——**benchmark 完整性 100%，缺失的 4 篇仅为普通干扰文档**。语料内容存在两种节标题风格（`Overview` 短行式与 `Purpose\n-----` 下划线式），均在正文中有明确结构边界，不影响分块。

### 1.2 评测题入仓（S1b）

已拷贝进 classpath（随仓库分发，MIT 许可）：

| 文件 | 内容 |
|---|---|
| `src/main/resources/eval/datasets/enterprise-rag-64.jsonl` | Confluence-only 64 题（basic 19/semantic 15/constrained 11/completeness 11/misc 3/intra 2/project 2/conflicting 1），字段：question_id/question_type/question/expected_doc_ids/gold_answer/answer_facts |
| `src/main/resources/eval/datasets/enterprise-info-not-found-20.jsonl` | 拒答题 20（辅助指标，见 Phase 5 注意） |

### 1.3 gitignore（已加）

`data/enterpriserag-md/`、`eval-answers/`、`enterpriserag-report.md` —— 语料可由脚本重建，不进 git。

---

## Phase 2 · 代码接入（✅ 2026-09-15 完成）

### 2.1 设计：新增 `EnterpriseRagRunner`

复用既有 eval 基础设施（H2 零依赖 + 内存向量 + ChatService 内部调用），与现有 `EvalRunner` 并列、按属性开关：

```java
@Slf4j
@Component
@Profile("eval")
@ConditionalOnProperty(name = "eval.bench", havingValue = "enterpriserag")
public class EnterpriseRagRunner implements CommandLineRunner { ... }
```

同步给现有 `EvalRunner` 加 `@ConditionalOnProperty(name = "eval.bench", havingValue = "internal", matchIfMissing = true)`——默认行为不变。

### 2.2 核心流程（三段）

```
① 引导语料（幂等）：
   ingestService.listDocuments() 非空则跳过
   → 遍历 ${eval.enterprise-rag.corpus-dir:./data/enterpriserag-md}/*.md
   → submitTask(fileName=dsid.md, stream) + processDocument(id)
   （5,190 篇，embedding 批 32 → 预计 10-20 分钟，每 500 篇打一条进度日志）

② 逐题评测：
   读 classpath enterprise-rag-64.jsonl
   → 每题 chatService.clearMemory(question_id)
   → chatService.chat(question_id, question, Mode.RAG, SilentSink)
   → 收集 result.sources() 的 docName → strip ".md" → document_ids（去重）

③ 输出：
   eval-answers/answers.jsonl：{question_id, answer, document_ids}（官方判分格式）
   enterpriserag-report.md：内部指标（文档级 Recall：|命中 ∩ expected| / |expected|）按题型分组
```

### 2.3 涉及文件

| 操作 | 文件 | 说明 |
|---|---|---|
| 新建 | `eval/EnterpriseRagRunner.java` | 上面的三段流程 |
| 修改 | `eval/EvalRunner.java` | 加 @ConditionalOnProperty 开关 |
| 修改 | `eval/EvalProperties.java` | 扩展 `bench` 开关 + `enterpriseRag` 嵌套配置 |
| 修改 | `src/main/resources/application-eval.yaml` | 追加下方配置块 |
| 新建 | `eval/EnterpriseRagCase.java`（record） | question_id/question_type/question/expected_doc_ids |

`application-eval.yaml` 追加：

```yaml
eval:
  bench: ${EVAL_BENCH:internal}          # internal | enterpriserag
  enterprise-rag:
    corpus-dir: ${EVAL_ERAG_CORPUS:./data/enterpriserag-md}
    answers-path: ${EVAL_ERAG_ANSWERS:./eval-answers/answers.jsonl}
    report-path: ${EVAL_ERAG_REPORT:./enterpriserag-report.md}
rag:
  chunk-size: 500        # 覆盖 eval profile 的 1000/0，对齐主配置（线上参数）
  chunk-overlap: 50
  min-chunk-size: 80
```

### 2.4 两个实现细节

1. **mode 用 RAG（kb）而非 Agent**：先测检索+生成基线；Agent 模式留作消融对比（工具循环会改变行为，两套数字分开报）
2. **docName 对齐**：入库时文件名 = `dsid.md`，sources 的 docName 即 `dsid.md`，写 answers.jsonl 前统一 strip `.md`——**这是官方判分 document recall 能否算出的命门**

### 2.5 真实栈模式支持（2026-09-15 补充）

为让同一 Runner 既跑零依赖（eval profile）又跑真实栈（正常 profile），落地三处改动：

1. **`EnterpriseRagRunner` 去掉 `@Profile("eval")`**——只留 `@ConditionalOnProperty(eval.bench=enterpriserag)`。正常 profile 下不加载 eval 的 `@Primary` 伪向量，embedding 自动回落到真实 `EmbeddingClient`（Qwen3-Embedding-8B）。
2. **语料引导改为逐文档幂等**（按文档名判断，非 listDocuments 判空）——真实栈 PG 持久化下断点续跑正确，且不会因主库有用户文档而误跳过引导。
3. **`eval` 配置块迁移到 `application.yaml`**（跨 profile 生效，环境变量占位符），`application-eval.yaml` 只保留 eval profile 专属的 llm-judge/report-path；`rag.data-dir` 加 `${RAG_DATA_DIR:./data}` 占位符供评测隔离数据目录。

---

## Phase 3 · 运行（⬜ 约 1 小时）

### 3.1 零依赖模式（H2 + 伪向量 + 内存向量，无需基础设施）

```bash
EVAL_BENCH=enterpriserag LLM_API_KEY=sk-xxx \
  ./mvnw spring-boot:run -Dspring-boot.run.profiles=eval
```

检查点：日志「语料引导完成」→ 逐题进度（64 题）→ 产物 `eval-answers/answers.jsonl` 64 行 + `enterpriserag-report.md`。

> 注意：此模式 embedding 走 eval profile 的 `@Primary` 词法伪向量（零成本），BM25 走真实 SQLite FTS5——只用于验证代码逻辑与 BM25 召回，**语义向量分数不代表真实 Qwen3-Embedding-8B**。

### 3.2 真实栈模式（PG + 真实 embedding + Qdrant，README 数字用）

前置：docker compose 基础设施已在跑（postgres/qdrant/redis）。**评测必须隔离数据空间**，避免污染主库（主库已有用户文档）与主 Qdrant collection。

**Step 1 · 建独立评测库**

```bash
docker exec agentic-rag-postgres psql -U postgres -c "DROP DATABASE IF EXISTS agentic_rag_eval;"
docker exec agentic-rag-postgres psql -U postgres -c "CREATE DATABASE agentic_rag_eval;"
cat src/main/resources/db/init.sql | docker exec -i agentic-rag-postgres psql -U postgres -d agentic_rag_eval
```

**Step 2 · 本地起 Runner（正常 profile，跑完即退出）**

```bash
EVAL_BENCH=enterpriserag \
LLM_API_KEY=sk-xxx \
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/agentic_rag_eval \
SPRING_DATASOURCE_USERNAME=postgres \
SPRING_DATASOURCE_PASSWORD=postgres \
RAG_DATA_DIR=./target/erag-real-data \
RAG_VECTOR_QDRANT_COLLECTION_PREFIX=erag_eval_chunks \
RAG_MEMORY_TYPE=memory \
./mvnw spring-boot:run \
  -Dspring-boot.run.arguments="--spring.main.web-application-type=none"
```

三个隔离点（缺一不可，否则与主环境互相覆盖）：
| 资源 | 隔离手段 | 说明 |
|---|---|---|
| PG 库 | `agentic_rag_eval` 独立库 | documents/chunks 表隔离（主库 agentic_rag 有用户文档） |
| Qdrant collection | `collection-prefix=erag_eval_chunks` | point id = chunk id，独立库 chunk id 会与主库冲突，必须换前缀 |
| 本地文件 + bm25 | `RAG_DATA_DIR=./target/erag-real-data` | 独立 files 目录 + bm25.db，不碰主 ./data |

`--spring.main.web-application-type=none` 让 Runner 不起 web server（避开 app 容器占用的 8081），评测跑完自动退出。

**Step 3 · 检查产物**：同零依赖模式。此模式 embedding 走真实 Qwen3-Embedding-8B（~13M token），入库耗时约 10-20 分钟。

**清理（可选）**：跑完 `docker exec agentic-rag-postgres psql -U postgres -c "DROP DATABASE agentic_rag_eval;"`，删除 `target/erag-real-data` 与 Qdrant 的 erag_eval_chunks collection 即可。

**中断恢复**：语料引导已改为**逐文档幂等**（按文档名判断，非 listDocuments 判空）——真实栈 PG 持久化下中途挂掉，重启会只导入缺失的剩余语料，不重复、不遗漏。

---

## ⚠️ 已知坑（2026-09-16 踩过，已修复）

1. **题目字段映射**：`EnterpriseRagCase` record 字段必须显式加 `@JsonProperty("question_id")` 等注解——题目 jsonl 是 snake_case（question_id / question_type / expected_doc_ids），而 record 字段是 camelCase，Jackson 默认不映射，会反序列化成 null。症状：答题日志 `评测进度 1/64: null (null)`、answers.jsonl 的 question_id 全 null（官方判分无法关联题目）。
2. **时间估算修正**：真实栈 embedding 入库实测 3-5 小时（非早期估算的 10-20 分钟），chunk 总数约 7.4 万、token 约 37M、成本约十几元。语料已入库后重跑会幂等跳过引导，只跑答题+判分约 30-40 分钟。
3. **IDE 后台编译干扰**：IDE（Trae/VS Code Java 扩展）会自动用 ECJ 编译到 `target/classes`，ECJ 编坏 record（questionId() 访问器丢失）覆盖 maven 产物，导致 `spring-boot:run` 启动报 `Unresolved compilation problem`；同时 IDE 的"保存时整理 import"可能误删 `@JsonProperty` 的 import，导致 maven 编译报"找不到符号"。**跑 maven 前务必：关闭 IDE 自动构建（`java.autobuild.enabled=false`）+ 关闭保存时优化 import，或直接关闭 IDE 用终端独占编译**。修复：只删 `target/classes`（⚠️ 绝不能 `mvn clean`——会连带删掉 `target/erag-real-data/bm25.db` 业务数据）+ 补回 import + `./mvnw compile`。

---

## Phase 4 · 官方判分（⬜ 约 30-60 分钟）

> 2026-09-16 已完成硅基流动适配：新增 `src/llm/openai_compatible_llm.py`（Chat Completions API，剥离 `<think>` 块）+ `factory.py` 注册 `openai_compatible` provider。已用 GLM-5.3 真实冒烟验证（judge 式 JSON 输出 → extract_json 解析全通）。venv 已建于仓库 `.venv`（openai 3.14.1 + pydantic）。

```bash
cd /Users/miaobao/agent-projects/EnterpriseRAG-Bench-main

# 1) 环境（venv 已建好，直接激活；key 从 agentic-rag/.env 加载）
source .venv/bin/activate
set -a; source /Users/miaobao/agent-projects/agentic-rag/.env; set +a

# 2) judge 配置（硅基流动 GLM-5.3）
export LLM_PROVIDER=openai_compatible
export LLM_BASE_URL=https://api.siliconflow.cn/v1
export LLM_MODEL_NAME=zai-org/GLM-5.3
export CHEAP_LLM_MODEL_NAME=zai-org/GLM-5.3

# 3) 判分（--no-correction 纯打分；--resume 支持断点）
python -m src.scripts.answer_evaluation.metrics_based_eval \
  --answers-file /Users/miaobao/agent-projects/agentic-rag/eval-answers/answers.jsonl \
  --questions-file questions.jsonl \
  --results-file answer_evaluation/results-erag.json \
  --no-correction --parallelism 4
```

**judge 选型**：
- 无 OpenAI key 时（当前方案）：`LLM_PROVIDER=openai_compatible` + 硅基流动 key，judge 用 GLM-5.3（与生成同模型家族，报告中注明即可）
- 有 OpenAI key 时：`LLM_PROVIDER=openai` + `LLM_MODEL_NAME=gpt-4o-mini` 等可用模型（脚本默认 gpt-5.4/gpt-5-mini 普通账号多半没有，须改）

**输出**：`results-erag.json` —— correctness / completeness（answer_facts 命中率）/ document recall / invalid extra documents 四指标。

---

## Phase 5 · 报告与消融（⬜ 半天）

1. **主表**：四官方指标 × 十题型分组（64 题），与自有 eval-set 指标并列写进 `enterpriserag-report.md`
2. **README 表述**：
   > EnterpriseRAG-Bench（Confluence 子集，5,190 文档 / 64 题）：Correctness xx% · Doc Recall@top5 xx% · Completeness xx%
3. **info_not_found 20 题**：第二批追加跑（answers 里正确行为=明确声明不可答）——**只作辅助指标单独报**，子库缺跨源干扰文档，拒答难度低于全量基准
4. **消融（可选加分项）**：单通道（vector-only / bm25-only）vs 混合 RRF 的 Doc Recall 对比——EnterpriseRAG 的 semantic 题型（去关键词重叠）正是混合检索价值的最强证据

---

## 故障排查

| 症状 | 原因 | 处置 |
|---|---|---|
| 转换脚本被 SIGKILL | uuid_index.json（58MB/51 万条目）解析内存峰值 | 已支持断点续跑，重跑至补齐；WARN 行为个别文件被内容过滤拦截，可忽略 |
| embedding 报维度错误 | Qwen3-Embedding-8B 为 4096 维 | eval 模式内存向量库动态适配；qdrant 模式检查 collection 维度 |
| 英文题 BM25 召回偏低 | FTS5 bigram 分词为中文优化，英文可用但非最优 | 已知限制，报告注明；后续可按语言切换 tokenizer |
| 判分报 Responses API 错误 | judge 模型不支持 Responses API | 已解决：用 `LLM_PROVIDER=openai_compatible`（2026-09-16 已打补丁，走 Chat Completions） |
| 某题 document_ids 为空 | 检索 topN 未命中任何 expected 文档 | 正常现象（正是要测的），Doc Recall 记 0；若全量空检查 docName strip 逻辑 |
| answers.jsonl 缺行 | 评测中途挂 | Runner 逐题追加写（勿攒内存最后写）；判分 --resume 续跑 |

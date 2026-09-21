# Agentic RAG

Agentic RAG 平台：M6 性能优化与泛化性建设。

## 评测基线（EnterpriseRAG-Bench Confluence 64 题）

**评测环境**：Judge=GLM-5.3, Embedding=Qwen3-8B, LLM=GLM-5.3, benchmark=EnterpriseRAG-Bench-confluence-64

### Phase 1（BM25 修复前 — 纯向量成绩）

> 修复前 `needsRebuild()` 每次启动清空 BM25 索引，RRF 静默退化为纯向量单通道。

| 指标 | 原始成绩 | 说明 |
|------|----------|------|
| Recall | 48.37% | 纯向量，不含 BM25 贡献 |
| Completeness | 29.73% | |
| Correctness | 14.06% | constrained 0/11 |
| BM25 非空率 | 0%（Bug） | 索引被误判重建 |

### Phase 1（BM25 修复后 — 通道消融对比）

> SCHEMA_VERSION 戳 + 表结构判据修复，BM25 真正参与 RRF。
> 另修复语料转换 bug：43% 文档（2248/5187 篇）正文字段读取错误导致只剩标题桩，
> 修复后 BM25 索引从 7.3 万行增至 13 万行。以下为完整语料上的官方数据。

| 通道 | Recall | Completeness | Correctness | 备注 |
|------|--------|-------------|-------------|------|
| 纯向量 | 49.48% | 43.71% | 25.00% | 语义改述强，术语弱 |
| 纯 BM25 | 57.08% | 32.68% | 20.31% | 术语/编号精确匹配强 |
| RRF 融合（默认） | **61.71%** | 44.04% | 26.56% | 比最强单通道 +4.6pp |

### Phase 2+3+4（检索扩展 + 生成优化叠加）

> 查询构造优化（AND→OR 降级 + 停用词 + Porter）+
> 检索扩展（Query 改写 + HyDE）+
> 生成层 Prompt 去保守化（全部轮次同等生效）

| 配置 | Recall | Completeness | Correctness | 答对题数 |
|------|--------|-------------|-------------|---------|
| hybrid 基线 | 61.71% | 44.04% | 26.56% | 17/64 |
| + Query 改写 | 61.00% | 41.43% | 26.56% | 17/64 |
| + HyDE | 65.89% | 49.91% | 34.38% | 22/64 |
| 改写 + HyDE（全开） | **66.20%** | **50.53%** | **35.94%** | **23/64** |

**HyDE 是最大单项增益**（+4.2pp recall / +7.8pp correctness）；Query 改写单独无增益，但与 HyDE 叠加仍有小幅提升。semantic 题型 recall 40%→53%、correctness 20%→40%。

**对比初始基线**（BM25 修复前 + 残缺语料）：Recall 48.37% → 66.20%（+17.8pp），Correctness 14.06% → 35.94%（+21.9pp），答对 9/64 → 23/64。

### AGENT 模式消融（r07）

| 配置 | Recall | Invalid Extra | 备注 |
|------|--------|---------------|------|
| RAG 模式（全开） | 66.20% | 3.09 | 引用恒 5 文档（finalTopN 上限） |
| AGENT 模式（ReAct 循环） | **75.22%** | 10.27 | 多轮检索打破 5 文档天花板，constrained recall 95.5% |

> r07 暴露一个真实工程 bug：AgentLoop 终答轮撤掉工具后，模型以纯文本模仿 `<tool_call` 格式输出，
> 41/64 题终答被污染——correctness/completeness 数字不可用于结论（详见 docs/issues 归因分析）。
> 修复方向（agentic 检索 + reranker 精排 + RAG 合成终答）即榜首系统的架构形态。

### Phase 7（Rerank 精排 + Agent 终答修复）

> 接入硅基流动 `BAAI/bge-reranker-v2-m3` rerank 模型，HybridRetriever 在 RRF 融合后 top 20 → rerank → top 5（fail-open 降级）；
> AgentLoop 检测终答 `<tool_call` 泄漏 → 用累积 sources + rerank 剪枝 → RAG 合成干净答案。
> r08/r07b 已完成实测（各 64 题、判分零网络污染），数字如下。

| 轮次 | 配置 | Recall | Completeness | Correctness | Invalid | 答对 | 泄漏题数 |
|------|------|--------|-------------|-------------|---------|------|---------|
| r06 | RAG + 改写 + HyDE | 66.20% | 50.53% | 35.94% | 3.09 | 23/64 | 0/64 |
| r08 | r06 + **rerank** | 66.85% | 52.98% | 34.38% | 3.02 | 22/64 | 0/64 |
| r07 | AGENT + 改写 + HyDE | 75.22% | 35.28% | 32.81† | 10.27 | 21/64 | 41/64 |
| r07b | r07 + rerank + **修复泄漏** | **77.38%** | 47.91% | 40.62% | 9.34 | 26/64 | **0/64** |
| r07c | r07b + **引用裁剪**(top8) | 76.39% | 51.83% | 39.06% | 5.44 | 25/64 | 0/64 |
| **r07d** | r07c 裁剪调至 **top5** | 68.28% | **53.29%** | **43.75%** | **3.27** | **28/64** | 0/64 |

注：†表示 r07 的 32.81% 因 41 题泄漏作废，r07b 起为有效数字。

**r07d 是最终最佳配置**：答对 28/64、correctness 43.75%、completeness 53.29% 三项历史最高，invalid extra 3.27 接近 RAG 模式水平（3.09）。终答泄漏 41/64 → 0/64（32 题触发检测走 RAG 合成降级）。

**三轮裁剪消融揭示的核心规律——更少引用 = 更高 correctness**：top20（不裁剪）corr 40.62 → top8 corr 39.06 → **top5 corr 43.75**。裁剪不仅改善引用精度指标，还通过减少上下文噪声直接提升生成质量（semantic 题型 corr 60%）。代价是 recall 77.38→68.28（top5 上限压制多 gold 文档题，completeness 题型 recall 38.2%）。`RAG_AGENT_CITATION_TOP_N`（默认已固化 5）提供 recall/corr 调节旋钮。

**r08 的关键认知（rerank 在 RAG 模式为何近乎无效）**：invalid extra 统计的是"引用的文档里几个非 gold"——rerank 只在 top-20 内部重排、引用数不变；且 benchmark 陷阱文档是语义极近的近重复冲突版，cross-encoder 同样给高分。rerank 的真正价值在 agent 模式剪枝（r07 累积 2-20 引用），不在 RAG 模式增益。

### Phase 5（泛化性评估，2026-09-21 实测）

> 用最终配置（r07d：AGENT + 改写 + HyDE + rerank + 引用裁剪 top5）跑三类扰动变体（各 64 题，gold 文档与题型零漂移），对照基线为 r07d 原题成绩。三轮判分均零网络污染。

| 变体类型 | Recall | Comp | Corr | 答对 | Recall Drop | 验收门槛 | 判定 |
|----------|--------|------|------|------|-------------|----------|------|
| 原始（r07d 基线） | 68.28% | 53.29% | 43.75% | 28/64 | — | — | — |
| 去术语化（英文口语） | 40.23% | 40.71% | 26.56% | 17/64 | **-28.1pp** | < 15pp | ❌ |
| 短查询（关键词序列） | 55.72% | 37.29% | 25.00% | 16/64 | **-12.6pp** | < 10pp | ❌（接近） |
| 中译（英文语料中文提问） | 21.88% | 23.43% | 14.06% | 9/64 | -46.4pp | 参考 | — |

**泛化性归因（逐题型对比 + 零召回统计）**：

- **主要瓶颈是查询侧语义鸿沟，不是通道缺陷**：v1 零召回 31/64 题、v2 零召回 21/64 题。constrained 题型最抗造（v1 仅 -27pp，因含工单号/日期等 BM25 可锚定的精确 token）；semantic 题型最脆弱（v2 短查询下 73.3%→33.3%，改写/ HyDE 依赖完整问句，关键词序列使扩展质量塌缩）。
- **跨语言是独立失效域**：v3 中译 constrained/completeness 题型 recall 归零（中文查询 vs 英文语料，BM25 完全失效，embedding 跨语言对齐不足）。这是参考项（语料为英文），但说明系统没有跨语言能力。
- **启示**：查询侧扩展（改写/HyDE）在原题上增益明显，但对"不像原题"的问法泛化不足——下一步方向是 agent 规划期的查询理解前置（口语→专业检索词重写），而非继续堆叠同构扩展。

### 已知短板（诚实披露）

- **泛化性（Phase 5 实测，最大短板）**：去术语化变体 recall -28.1pp、短查询 -12.6pp——查询侧扩展（改写/HyDE）对"不像原题"的问法泛化不足；跨语言（中文问英文库）constrained/completeness recall 归零
- completeness 题型答对率仍低（r07b 1/11）：答案子项覆盖不全（检索已到位，生成层拼全能力不足）
- constrained correctness 27%（r07b）：细节复述（ticket 号/日期）仍常遗漏，尽管 recall 已达 95.5%
- agent 模式 invalid extra 3.27（r07d，接近 RAG 模式 3.09）：本质是多轮检索的结构性代价——extra ≈ 引用数 - gold 数；`RAG_AGENT_CITATION_TOP_N`（默认 5）提供 recall/corr 调节旋钮；benchmark 陷阱文档为语义极近的冲突版，rerank 无法区分
- rerank 在 RAG 模式无增益（r08 实测 corr -1.6pp）：top-20→top-5 重排不改变引用基数，已保留（免费）但价值定位在 agent 剪枝

## 项目结构

```
src/main/java/com/agenticrag/
├── agent/        Agent 循环（M3）
├── api/          REST 接口
├── config/       配置属性
├── eval/         评测框架
├── intent/       意图分类
├── llm/          LLM 客户端（OpenAI 兼容）
├── memory/       会话记忆
├── multiagent/   多 Agent 编排
├── rag/
│   ├── dto/      数据对象
│   ├── index/    BM25 + 向量索引（含 TextAnalyzer/PorterStemmer）
│   ├── ingest/   文档入库
│   ├── retrieve/ 混合检索（含 QueryRewriter/HydeExpander）
│   └── storage/  文件存储
├── service/      聊天服务（含 Phase 4 Prompt）
└── tool/         工具系统
```

## 四路消融评测操作

```bash
# Phase 3 消融：原始 → +改写 → +HyDE → +改写+HyDE

# 原始（全关）
mvn test -Dtest=EnterpriseRagRunnerTest \
  -Dspring.profiles.active=eval \
  -DRAG_RETRIEVAL_MODE=hybrid

# +改写
RAG_RETRIEVAL_REWRITER_ENABLED=true mvn test -Dtest=... ...

# +HyDE
RAG_RETRIEVAL_HYDE_ENABLED=true mvn test -Dtest=... ...

# +改写+HyDE
RAG_RETRIEVAL_REWRITER_ENABLED=true RAG_RETRIEVAL_HYDE_ENABLED=true mvn test -Dtest=... ...
```

## Phase 5 泛化性评估操作

```bash
# 1. 生成变体（三类各 64 题，输出到 data/derived/）
python3 scripts/generate_query_variants.py \
  --input src/main/resources/eval/datasets/enterprise-rag-64.jsonl \
  --out ./data/derived \
  --base-url https://api.siliconflow.cn/v1 \
  --api-key $LLM_API_KEY \
  --model Qwen/Qwen2.5-7B-Instruct

# 2. 用变体跑评测（EVAL_ERAG_QUESTIONS 指定外部题目文件，空则回退内置 64 题）
EVAL_ERAG_QUESTIONS=data/derived/questions-variants-dejargon.jsonl \
EVAL_ERAG_CHAT_MODE=agent ROUNDS_DIR=eval-answers/rounds-era2 \
  ./scripts/run-erag-round.sh v1-dejargon hybrid 1 1 1
ROUNDS_DIR=eval-answers/rounds-era2 ./scripts/run-erag-judge.sh v1-dejargon

# 3. 对比原始 recall 计算 Recall Drop（对照基线 r07d）
```

## 环境变量

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `LLM_API_KEY` | — | **必需**，LLM API Key |
| `RAG_RETRIEVAL_MODE` | `hybrid` | 检索模式：hybrid/bm25/vector |
| `RAG_RETRIEVAL_REWRITER_ENABLED` | `false` | Phase 3.1 查询改写开关 |
| `RAG_RETRIEVAL_REWRITER_MIN_TOKENS` | `15` | 触发改写的最小 token 数 |
| `RAG_RETRIEVAL_HYDE_ENABLED` | `false` | Phase 3.2 HyDE 开关 |
| `RAG_RETRIEVAL_RERANK_ENABLED` | `false` | Phase 7 Rerank 精排开关 |
| `RAG_RETRIEVAL_RERANK_MODEL` | `BAAI/bge-reranker-v2-m3` | Rerank 模型名（硅基流动） |
| `RAG_RETRIEVAL_RERANK_CANDIDATE_SIZE` | `20` | 送入 rerank 的候选数 |
| `RAG_RETRIEVAL_RERANK_FINAL_TOP_N` | _空_ | 精排后保留数，空则回退 `final-top-n` |
| `RAG_DATA_DIR` | `./data` | SQLite 本地数据目录 |

## 开发

```bash
# 编译
./mvnw compile

# 全量测试
./mvnw test

# 仅跑新增 M6 测试
./mvnw test -Dtest="TextAnalyzerTest,QueryRewriterTest,HydeExpanderTest,Bm25StoreRebuildTest"

# Phase 7 Rerank + Agent 终答修复测试
./mvnw test -Dtest="RerankClientTest,HybridRetrieverTest,AgentLoopTest"

# 启动
./mvnw spring-boot:run
```
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

> r07 暴露一个真实工程 bug：AgentLoop 终答轮撤掉工具后，模型以纯文本模仿 `<tool_call>` 格式输出，
> 41/64 题终答被污染——correctness/completeness 数字不可用于结论（详见 docs/issues 归因分析）。
> 修复方向（agentic 检索 + reranker 精排 + RAG 合成终答）即榜首系统的架构形态。

### Phase 5（泛化性评估）

| 变体类型 | Recall | Recall Drop | 验收门槛 |
|----------|--------|-------------|----------|
| 原始（全开配置） | 66.20% | — | — |
| 去术语化 | TBD | TBD% | < 15% |
| 短查询 | TBD | TBD% | < 10% |
| 中译 | TBD | TBD% | 参考 |
| 通道独立贡献率 | TBD% | — | > 10% |

### 已知短板（诚实披露）

- completeness 题型答对率 0-1/11：答案子项覆盖不全（检索已到位，生成层拼全能力不足）
- constrained correctness 18-27% 波动：细节复述（ticket 号/日期）仍常遗漏
- RRF 融合仅比最强单通道高 4.6pp：两通道互补性未吃满，加权融合/按题型路由是下一步

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
# 1. 生成变体（三类各 64 题）
python3 scripts/generate_query_variants.py \
  --input ./data/benchmark/questions.jsonl \
  --out ./data/derived \
  --base-url https://api.siliconflow.cn/v1 \
  --api-key $LLM_API_KEY \
  --model Qwen/Qwen2.5-7B-Instruct

# 2. 用变体跑评测（替换 questions.jsonl 路径）
# 3. 对比原始 recall 计算 Recall Drop
```

## 环境变量

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `LLM_API_KEY` | — | **必需**，LLM API Key |
| `RAG_RETRIEVAL_MODE` | `hybrid` | 检索模式：hybrid/bm25/vector |
| `RAG_RETRIEVAL_REWRITER_ENABLED` | `false` | Phase 3.1 查询改写开关 |
| `RAG_RETRIEVAL_REWRITER_MIN_TOKENS` | `15` | 触发改写的最小 token 数 |
| `RAG_RETRIEVAL_HYDE_ENABLED` | `false` | Phase 3.2 HyDE 开关 |
| `RAG_DATA_DIR` | `./data` | SQLite 本地数据目录 |

## 开发

```bash
# 编译
./mvnw compile

# 全量测试
./mvnw test

# 仅跑新增 M6 测试
./mvnw test -Dtest="TextAnalyzerTest,QueryRewriterTest,HydeExpanderTest,Bm25StoreRebuildTest"

# 启动
./mvnw spring-boot:run
```
# M2 · RAG 方案（解析 / 分块 / 入库 / 双通道检索）

> 前置：M1 已完成（LLM 流式对话 + 聊天页）
> 目标：上传 PDF → 解析 → 分块 → 向量化入库 → 向量 + BM25 双通道检索 → RRF 融合 → 回答带引用
> 验收：上传一份 PDF，提问相关的问题，回答基于文档内容且带引用编号

## 一、范围

**做**
- PDF / TXT / Markdown 解析入库
- 分块：固定大小（含重叠）+ 结构化（按标题/段落边界）
- Embedding：OpenAI 兼容接口（/embeddings）
- 向量存储：Qdrant（Docker 单容器，官方 gRPC 客户端）；`VectorStore` 接口化可插拔（另附内存实现供零依赖测试）
- BM25：SQLite FTS5（零外部服务；中文用 bigram 分词技巧）
- 混合检索：向量 topK + BM25 topK → RRF 融合 → 取 top N
- 聊天链路接入：检索到内容注入 system prompt，要求模型带 [n] 引用

**不做**
- 不接 ES / 知识图谱 / 联网搜索
- 不重排模型（先用 embedding 相似度兜底，预留 Rerank 接口）
- 不做文档管理后台（只留 API + 数据库表）

## 二、新增依赖（pom.xml）

| 依赖 | 版本建议 | 用途 |
|---|---|---|
| org.apache.pdfbox:pdfbox | 3.0.3 | PDF 文本抽取 |
| org.xerial:sqlite-jdbc | 3.46+ | SQLite FTS5（BM25） |
| org.postgresql:postgresql | 42.7+ | PostgreSQL 元数据驱动（runtime） |
| io.qdrant:client | 1.13+ | Qdrant 官方 gRPC 客户端 |
| org.springframework.boot:spring-boot-starter-jdbc | 跟随 Boot | JdbcTemplate + HikariCP |

## 三、包结构（rag/ 下新增）

```
rag/
├── ingest/
│   ├── DocumentParser.java       — 接口：parse(InputStream) → 纯文本
│   ├── PdfDocumentParser.java    — PDFBox 实现
│   ├── TextDocumentParser.java   — txt/md 实现
│   ├── Chunker.java              — 接口：split(text, meta) → List<Chunk>
│   ├── FixedChunker.java         — 固定长度 + 重叠（默认 500 字 / overlap 50）
│   ├── StructureChunker.java     — 按标题/段落边界切，超长再回退 Fixed
│   └── IngestService.java        — 编排：parse → chunk → embed → 写 pg + FTS
├── index/
│   ├── EmbeddingClient.java      — OpenAI 兼容 /embeddings（批量，默认 32/批）
│   ├── VectorStore.java          — 接口：saveChunks / searchByVector（可插拔）
│   ├── QdrantStore.java          — Qdrant 实现（gRPC，payload 带 content/docName）
│   ├── InMemoryVectorStore.java  — 内存余弦实现（零依赖，单测/演示兜底）
│   ├── Bm25Store.java            — SQLite FTS5：saveChunks / search
│   └── VectorSearchResult.java   — record(chunkId, content, docName, score)
├── retrieve/
│   ├── HybridRetriever.java      — 双通道 + RRF 融合 + top N
│   └── RetrievedChunk.java       — record(chunkId, content, docName, rrfScore)
└── dto/
    ├── Chunk.java                — record(id, documentId, seq, content)
    └── Document.java             — record(id, name, chunkCount, status)
```

## 四、数据模型

### PostgreSQL（纯元数据，不含向量）

```sql
CREATE TABLE documents (
    id          bigserial PRIMARY KEY,
    name        varchar(512) NOT NULL,
    chunk_count int          NOT NULL DEFAULT 0,
    status      varchar(16)  NOT NULL DEFAULT 'READY',
    created_at  timestamptz  NOT NULL DEFAULT now()
);

CREATE TABLE chunks (
    id          bigserial PRIMARY KEY,
    document_id bigint      NOT NULL REFERENCES documents(id),
    seq         int         NOT NULL,
    content     text        NOT NULL
);
```

- PG 只承担文档/chunk 元数据与内容管理（入库、文档列表、管理查询）
- chunk 的自增 id 同步作为 Qdrant 的 point id（两侧对齐）

### Qdrant（向量 + payload）

```
collection: agentic_rag_chunks
  向量维度: 1536（按所选 embedding 模型调整）
  距离度量: Cosine
  HNSW:     m=16, ef_construct=100（查询侧可 SET ef=100）

point 结构:
  id      = PG chunks.id（对齐）
  vector  = embedding
  payload = { content, docName, seq, documentId }
```

- **payload 直接带内容**：检索命中即可拿到 content/docName，**不回表 PG**——检索链路一步到位
- 本地部署：`docker run -p 6333:6333 -p 6334:6334 qdrant/qdrant`（6333 REST/健康检查 `/healthz`，6334 gRPC 供 Java 客户端）
- 与 PostgreSQL 零冲突：独立进程/端口/存储，职责切分清晰（PG=关系元数据，Qdrant=向量）

### SQLite（FTS5，文件 ./data/bm25.db，首次启动自动创建）

```sql
CREATE VIRTUAL TABLE chunks_fts USING fts5(
    doc_name, seq, content, tokenize = 'unicode61'
);
```

**中文 BM25 关键设计（bigram 分词）**：FTS5 的 unicode61 对中文按空白切词，整句中文会变成一个 token，BM25 失效。入库与查询两侧做同一处理：
- 非 ASCII 字符每连续 2 个字符之间插入空格（`你好世界` → `你好 好世 世界`）
- 中文与英文/数字/空白交界处也插空格
- 查询词同样 bigram 化后再走 `MATCH`
效果：中文按滑窗二字词匹配，是工程上验证过的低成本中文全文检索方案，面试可主动讲。

## 五、核心链路

### 入库（IngestService）

```
POST /api/ingest (multipart file)
  → DocumentParser 按扩展名选择实现，抽取纯文本
  → Chunker.split：先 StructureChunker（按标题/段落），段内超 500 字回退 FixedChunker（overlap 50）
  → EmbeddingClient.embedBatch(chunks, 每批 32)
  → PG 写 documents + chunks 元数据（拿自增 chunk id）
  → VectorStore.saveChunks（Qdrant：point id = PG chunk id，payload 带 content/docName/seq）
  → Bm25Store.saveChunks（FTS：bigram 化后 insert）
  → 返回 {documentId, name, chunkCount}
```

### 检索（HybridRetriever）

```
query
  → EmbeddingClient.embed(query) → VectorStore.searchByVector(topK=10)   [通道 A：Qdrant 检索，payload 直接返回 content/docName，不回表]
  → Bm25Store.search(bigram(query), topK=10)                             [通道 B]
  → RRF 融合：score = Σ 1/(60 + rank_in_channel)   （k=60 经典值）
  → 按 RRF 分排序取 top 5
  → 组装 context：每段前缀 [1][2]... 编号 + 文档名
```

### 聊天接入（ChatController 升级）

```
用户提问 → HybridRetriever.retrieve(query)
  → 无结果：走原纯对话链路（不注入 context）
  → 有结果：system prompt 追加 context 段 + 指令「基于上下文回答，并在句末标注 [n] 引用；上下文不足时明确说明」
  → done 事件附带 sources 数组（{n, docName, snippet}）——M4 前端渲染，M2 先透传
```

## 六、新增/修改文件清单

| 操作 | 文件 | 说明 |
|---|---|---|
| 修改 | pom.xml | +5 个依赖 |
| 新建 | docker-compose.yml | postgres（元数据，含 init SQL 挂载）+ qdrant（向量） |
| 新建 | resources/db/init.sql | 建表（纯元数据，无向量列） |
| 新建 | config/RagProperties.java | chunkSize/overlap/topK/rrfK/向量维度/dataDir + vector.type(qdrant\|memory)/qdrant.host/port/collection 配置 |
| 新建 | rag/ 下全部类（见包结构） | 核心实现 |
| 新建 | api/IngestController.java | POST /api/ingest + GET /api/documents |
| 修改 | llm/ 下 + EmbeddingClient 复用 LlmProperties（或拆 LlmProperties 的 embedding 段） | |
| 修改 | api/ChatController.java | 接入 HybridRetriever + sources 透传 |
| 修改 | application.yaml | +rag 配置段、spring.datasource（pg）、data.dir |
| 新建 | RagSmokeTest（test） | Mock EmbeddingClient 验证入库→检索链路 |

## 七、任务拆解

| 任务 | 步骤 | 验证 |
|---|---|---|
| T1 依赖与基础设施 | 加 5 个依赖；写 docker-compose.yml（postgres + qdrant）+ init.sql；`docker compose up -d` | `./mvnw compile` 通过；`psql` 可查 chunks 表；`curl localhost:6333/healthz` 返回 ok |
| T2 解析器 | DocumentParser 接口 + Pdf/Text 实现 | 单测：样例 PDF 解析出非空文本 |
| T3 分块器 | Chunker 接口 + Fixed/Structure 实现 | 单测：长文本切块数量/重叠正确，标题边界不切断 |
| T4 EmbeddingClient | OpenAI 兼容 /embeddings，批量+重试 | 单测（Mock HTTP）；真实 key 冒烟 |
| T5 VectorStore 双实现 | 接口 + QdrantStore（gRPC upsert/search，collection 自动创建）+ InMemoryVectorStore（余弦）；按 rag.vector.type 装配 | 集成测：写入 3 块 → 查回 top1 正确（Qdrant）；单测：内存实现同断言（零依赖） |
| T6 Bm25Store | SQLite FTS5 + bigram 化工具类 | 单测：中文 query 命中对应文档 |
| T7 HybridRetriever | RRF 融合 + top N | 单测：双通道结果融合排序正确 |
| T8 IngestService + Controller | 入库编排 + API；ChatController 接检索 | 端到端：上传 PDF → 提问 → 回答带 [n] |

## 八、验收标准

- [ ] `docker compose up -d` 拉起 postgres + qdrant，init.sql 自动建表，qdrant `/healthz` 健康
- [ ] 上传一份 ≥3 页 PDF，`/api/documents` 可查 chunkCount > 0
- [ ] 针对文档内容提问，回答包含文档信息且句末带 [1]/[2] 引用
- [ ] 针对文档无关问题，正常走纯对话（不强行编造引用）
- [ ] 中文检索有效：中文 query 能在 BM25 通道命中中文文档
- [ ] 单元/集成测试全部通过，全量 `./mvnw test` 绿

## 九、关键取舍（面试可讲）

1. **Qdrant 独立向量库 + payload 免回表**：检索一步拿到 content/docName，PG 专注关系元数据；单容器部署轻于 Milvus（无需 etcd/MinIO），`VectorStore` 接口保证后续可换 pgvector/Milvus 而不侵入链路
2. **SQLite FTS5 替代 ES**：BM25 语义不变、零外部服务；中文 bigram 是经典工程技巧
3. **RRF 而非加权线性融合**：不需要调权重，对分数尺度不敏感
4. **先 embedding 相似度兜底重排**：M2 不接重排模型，但接口预留（`Reranker`），后续可插拔
5. **内存 VectorStore 兜底实现**：单测与零依赖演示不用起 Docker，配置一键切换

# Debug Session: spring-boot-startup
- **Status**: [OPEN]
- **Issue**: `./mvnw spring-boot:run` 启动失败，需要确认实际报错与根因。
- **Debug Server**: N/A（当前先用进程启动日志复现）
- **Log File**: N/A

## Reproduction Steps
1. 在项目根目录执行 `./mvnw spring-boot:run`
2. 观察启动日志中的第一处异常与堆栈

## Hypotheses & Verification
| ID | Hypothesis | Likelihood | Effort | Evidence |
|----|------------|------------|--------|----------|
| A | Spring 配置值与当前依赖/运行环境不兼容，导致启动期 Bean 初始化失败 | High | Low | Pending |
| B | 启动时依赖的外部资源（PostgreSQL / Qdrant / SQLite 文件）不可用，导致应用上下文刷新失败 | High | Low | Pending |
| C | 新补的测试/配置口径改动影响了主应用配置，触发运行时维度或参数校验异常 | Medium | Low | Pending |
| D | 某个 Bean 在 `@PostConstruct` 或构造阶段直接发起网络连接，因版本/端口问题启动失败 | High | Low | Pending |
| E | Maven 启动使用的本地环境变量与预期不一致，导致模型、向量维度或数据源配置异常 | Medium | Medium | Pending |

## Log Evidence
- `./mvnw spring-boot:run` 复现成功，应用在 Spring Context 初始化阶段失败。
- 直接异常：
  - `Error creating bean with name 'qdrantStore': Invocation of init method failed`
  - `Qdrant collection agentic_rag_chunks 向量维度为 1024，但当前配置 rag.embedding-dim=4096（embedding 模型 qwen_qwen3_embedding_8b）`
- 配置证据：
  - `application.yaml` / `application copy.yaml` 中 `rag.embedding-dim=${RAG_EMBEDDING_DIM:4096}`
  - 同时 `rag.vector.qdrant.collection=agentic_rag_chunks` 使用固定集合名
  - `QdrantStore.ensureCollection()` 启动时会校验已存在 collection 的实际维度与当前配置是否一致，不一致则直接抛异常

## Hypothesis Status
| ID | Hypothesis | Status | Evidence |
|----|------------|--------|----------|
| A | Spring 配置或 Bean 初始化不兼容 | Confirmed | `qdrantStore.init()` 在 Bean 初始化阶段抛出维度不匹配异常 |
| B | 外部资源不可用 | Rejected | Qdrant 可连通，失败不是连接拒绝，而是 collection 元数据校验失败 |
| C | 最近配置改动触发维度或参数校验异常 | Confirmed | 当前配置 4096，而现有 collection 维度为 1024 |
| D | Bean 启动阶段联网失败 | Rejected | Bean 确实联网，但不是网络失败，而是版本/元数据检查后主动抛错 |
| E | 环境变量导致配置异常 | Inconclusive | 当前日志显示默认/实际值为 4096，但是否由环境变量覆盖仍未单独验证 |

## Verification Conclusion
当前启动失败的直接根因是：**本地 Qdrant 中已存在的 `agentic_rag_chunks` collection 维度为 1024，而应用当前配置期望 4096，`QdrantStore` 在启动时执行维度校验后主动抛错，导致 Spring Context 初始化失败。**

## Fix Applied
- 保持 `rag.embedding-dim=4096`
- 将 Qdrant 配置从固定 `collection=agentic_rag_chunks` 改为 `collection-prefix=agentic_rag_chunks`
- 让应用自动按 `collectionPrefix + modelSlug + embeddingDim` 生成独立 collection，避免与历史 1024 维集合冲突

## Post-Fix Evidence
- `./mvnw spring-boot:run` 启动成功
- 关键日志：
  - `Tomcat started on port 8081 (http) with context path '/'`
  - `Started AgenticRagApplication`

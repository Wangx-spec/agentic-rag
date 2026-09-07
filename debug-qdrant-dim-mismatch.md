# Debug Session: qdrant-dim-mismatch
- **Status**: [OPEN]
- **Issue**: 上传文档时 Qdrant upsert 报向量维度不匹配，应用配置为 4096，但 Qdrant collection 期望 1024。
- **Debug Server**: N/A
- **Log File**: N/A

## Reproduction Steps
1. 将 `rag.embedding-dim` 改为 `4096`
2. 将 `llm.embedding-model` 改为 `Qwen/Qwen3-Embedding-8B`
3. 启动应用并上传文档
4. 观察到 Qdrant `Upsert operation failed`，提示 `expected dim: 1024, got 4096`

## Hypotheses & Verification
| ID | Hypothesis | Likelihood | Effort | Evidence |
|----|------------|------------|--------|----------|
| A | Qdrant collection 在此前已按 1024 维创建，`ensureCollection()` 只检查存在性，不会自动重建 | High | Low | Confirmed |
| B | `EmbeddingClient` 实际仍返回旧模型维度，与配置不一致 | Medium | Medium | Pending |
| C | 配置已改为 4096，但当前运行实例未重启，仍使用旧配置 | Medium | Low | Pending |
| D | 上传的目标 collection 不是 `agentic_rag_chunks`，命中了其他旧 collection | Low | Low | Rejected |

## Log Evidence
- Qdrant collection `agentic_rag_chunks` 当前配置：`vectors.size = 1024`
- 应用配置：`rag.embedding-dim = 4096`
- 运行时报错：`expected dim: 1024, got 4096`

## Verification Conclusion
- 已确认是配置维度与现存 collection 维度不一致导致的 upsert 失败。

package com.agenticrag.rag.index;

import java.util.List;
import com.agenticrag.rag.dto.Chunk;

/**
 * 向量存储接口（可插拔：qdrant | memory，按 rag.vector.type 装配）
 * <p>
 * 计划方法：
 * - void saveChunks(long documentId, String docName, List&lt;Chunk&gt; chunks, List&lt;float[]&gt; vectors)   写入（payload 带 content/docName/seq）
 * - List&lt;VectorSearchResult&gt; searchByVector(float[] queryVector, int topK)                        相似检索
 * <p>
 * 实现：QdrantStore（gRPC，默认）、InMemoryVectorStore（余弦，零依赖兜底）
 */
public interface VectorStore {
    void saveChunks(long documentId, String docName, List<Chunk> chunks, List<float[]> vectors);

    List<VectorSearchResult> searchByVector(float[] queryVector, int topK);

    /** 按文档删除全部向量点（删除文档时三处联动之一），幂等 */
    void deleteByDocumentId(long documentId);
}

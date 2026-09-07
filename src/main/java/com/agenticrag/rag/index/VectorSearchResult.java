package com.agenticrag.rag.index;

/**
 * 向量检索结果
 * <p>
 * 计划字段：chunkId、content、docName、score（Qdrant 相似度或 BM25 分数）
 */
public record VectorSearchResult(
    Long chunkId,
    Long documentId,
    int seq,
    String content,
    String docName,
    double score
) {
}

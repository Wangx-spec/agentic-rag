package com.agenticrag.rag.retrieve;

/**
 * 融合后的检索结果
 * <p>
 * 计划字段：chunkId、content、docName、rrfScore、rank（组装 [n] 引用编号用）
 */
public record RetrievedChunk(
    Long chunkId,
    Long documentId,
    int seq,
    String content,
    String docName,
    double rrfScore,
    int rank
) {
}

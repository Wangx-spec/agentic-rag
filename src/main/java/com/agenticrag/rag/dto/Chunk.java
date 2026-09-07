package com.agenticrag.rag.dto;

/**
 * 文档分块
 * <p>
 * 计划字段：id（与 Qdrant point id 对齐）、documentId、seq（块序号）、content
 */
public record Chunk(
    Long id,
    Long documentId,
    int seq,
    String content
) {
}

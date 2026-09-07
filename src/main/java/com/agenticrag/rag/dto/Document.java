package com.agenticrag.rag.dto;

import java.time.Instant;

/**
 * 文档元信息
 * <p>
 * 计划字段：id、name、chunkCount、status（READY 等）
 */
public record Document(
        Long id,
        String name,
        int chunkCount,
        DocumentStatus status,
        Instant createdAt
) {

}

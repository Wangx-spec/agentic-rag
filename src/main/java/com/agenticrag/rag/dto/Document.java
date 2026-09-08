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
        String filePath,
        int chunkCount,
        DocumentStatus status,
        String errorMsg,
        Instant createdAt
) {

}

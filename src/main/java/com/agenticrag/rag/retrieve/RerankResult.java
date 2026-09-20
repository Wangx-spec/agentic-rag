package com.agenticrag.rag.retrieve;

/**
 * Rerank 单个结果：原始候选索引 + 相关性分数（0-1，由 API 按降序返回）。
 */
public record RerankResult(int index, double relevanceScore) {}

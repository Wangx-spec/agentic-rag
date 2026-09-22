package com.agenticrag.intent;

import java.util.Collections;
import java.util.List;

/**
 * AGENT 规划前的查询理解结果。
 */
public record QueryUnderstanding(
        Intent intent,
        double confidence,
        String normalizedQuery,
        List<String> subQueries
) {
    public QueryUnderstanding {
        subQueries = subQueries == null ? Collections.emptyList() : List.copyOf(subQueries);
    }
}

package com.agenticrag.llm.dto;

import java.util.Collections;
import java.util.List;

public record LlmResponse(String content, List<ToolCall> toolCalls) {

    public LlmResponse {
        toolCalls = toolCalls == null ? Collections.emptyList() : List.copyOf(toolCalls);
    }
    
    public static LlmResponse withContent(String content) {
        return new LlmResponse(content, Collections.emptyList());
    }

    public static LlmResponse withToolCalls(String content, List<ToolCall> toolCalls) {
        return new LlmResponse(content, toolCalls);
    }
}

package com.agenticrag.llm.dto;

import java.util.Collections;
import java.util.List;

/**
 * 对话消息（OpenAI 兼容格式）
 */
public record ChatMessage(String role, String content, String toolCallId, List<ToolCall> toolCalls) {

    public ChatMessage {
        toolCalls = toolCalls == null ? Collections.emptyList() : List.copyOf(toolCalls);
    }

    public ChatMessage(String role, String content) {
        this(role, content, null, Collections.emptyList());
    }

    public static ChatMessage system(String content) {
        return new ChatMessage("system", content, null, Collections.emptyList());
    }

    public static ChatMessage user(String content) {
        return new ChatMessage("user", content, null, Collections.emptyList());
    }

    public static ChatMessage assistant(String content) {
        return new ChatMessage("assistant", content, null, Collections.emptyList());
    }

    public static ChatMessage assistantWithToolCalls(String content, List<ToolCall> toolCalls) {
        return new ChatMessage("assistant", content, null, toolCalls);
    }

    public static ChatMessage tool(String toolCallId, String content) {
        return new ChatMessage("tool", content, toolCallId, Collections.emptyList());
    }
}

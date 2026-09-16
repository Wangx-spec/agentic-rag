package com.agenticrag.service;

public enum ChatMode {
    AUTO, PLAIN, RAG, AGENT, MULTI_AGENT;

    public static ChatMode from(String raw) {
        return switch (raw.toLowerCase()) {
            case "auto" -> AUTO;
            case "plain" -> PLAIN;
            case "rag" -> RAG;
            case "agent" -> AGENT;
            case "multi-agent" -> MULTI_AGENT;
            default -> throw new IllegalArgumentException("不支持的 mode: " + raw);
        };
    }
}

package com.agenticrag.llm;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

public final class ToolCallIds {

    private static final int MAX_LENGTH = 64;

    private ToolCallIds() {
    }

    public static String normalize(String id, String name, int index) {
        String cleaned = id == null ? "" : id.trim();
        if (cleaned.isBlank()) {
            return "call_" + hash((name == null ? "" : name) + "_" + index).substring(0, 12);
        }
        cleaned = cleaned.replaceAll("[^a-zA-Z0-9_-]", "_");
        if (cleaned.length() <= MAX_LENGTH) {
            return cleaned;
        }
        return cleaned.substring(0, 48) + "_" + hash(cleaned).substring(0, 8);
    }

    private static String hash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}

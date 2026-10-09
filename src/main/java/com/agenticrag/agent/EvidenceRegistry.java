package com.agenticrag.agent;

import com.agenticrag.rag.retrieve.RetrievedChunk;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * S3.2：会话级证据注册表，对外统一使用 [n] 编号。
 */
public class EvidenceRegistry {

    private final Map<String, Evidence> byId = new LinkedHashMap<>();

    public List<Evidence> registerDocumentChunks(List<RetrievedChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return List.of();
        }
        List<Evidence> result = new ArrayList<>();
        for (RetrievedChunk chunk : chunks) {
            if (chunk == null) {
                continue;
            }
            String preferredId = String.valueOf(chunk.rank());
            String id = byId.containsKey(preferredId) ? String.valueOf(nextId()) : preferredId;
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("docName", chunk.docName() == null ? "" : chunk.docName());
            metadata.put("chunkId", chunk.chunkId() == null ? "" : chunk.chunkId());
            metadata.put("seq", chunk.seq());
            Evidence evidence = new Evidence(
                    id,
                    EvidenceType.DOCUMENT_CHUNK,
                    "文档片段：" + chunk.docName() + " #" + chunk.seq(),
                    chunk.chunkId() == null ? "" : String.valueOf(chunk.chunkId()),
                    metadata
            );
            byId.put(id, evidence);
            result.add(evidence);
        }
        return result;
    }

    public Evidence registerSqlResult(String sql, List<String> columns, List<?> rows, boolean truncated) {
        int rowCount = rows == null ? 0 : rows.size();
        String summary = "SQL: " + abbreviate(sql, 80)
                + " → 列[" + String.join(", ", safeColumns(columns)) + "]"
                + " 首行" + firstRowSummary(rows)
                + "（" + rowCount + " 行" + (truncated ? "，已截断" : "") + "）";
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("sql", sql == null ? "" : sql);
        metadata.put("columns", columns == null ? List.of() : columns);
        metadata.put("rowCount", rowCount);
        metadata.put("truncated", truncated);
        return registerNext(EvidenceType.SQL_RESULT, summary,
                Integer.toHexString(String.valueOf(sql).hashCode()), metadata);
    }

    private static List<String> safeColumns(List<String> columns) {
        if (columns == null || columns.isEmpty()) {
            return List.of();
        }
        return columns.stream().map(column -> column == null ? "" : column).toList();
    }

    private static String firstRowSummary(List<?> rows) {
        if (rows == null || rows.isEmpty()) {
            return "{}";
        }
        Object first = rows.get(0);
        if (first instanceof List<?> values) {
            return values.toString();
        }
        return String.valueOf(first);
    }

    private static String abbreviate(String value, int maxChars) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= maxChars) {
            return normalized;
        }
        return normalized.substring(0, Math.max(1, maxChars - 1)) + "…";
    }

    public Evidence registerMemory(String summary, String payloadRef) {
        return registerNext(EvidenceType.MEMORY, summary, payloadRef, Map.of());
    }

    public Optional<Evidence> find(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    public List<Evidence> snapshot() {
        return List.copyOf(byId.values());
    }

    public boolean isEmpty() {
        return byId.isEmpty();
    }

    private Evidence registerNext(EvidenceType type, String summary, String payloadRef, Map<String, Object> metadata) {
        String id = String.valueOf(nextId());
        Evidence evidence = new Evidence(id, type, summary, payloadRef == null ? "" : payloadRef, metadata);
        byId.put(id, evidence);
        return evidence;
    }

    private int nextId() {
        return byId.keySet().stream()
                .map(EvidenceRegistry::parseId)
                .max(Comparator.naturalOrder())
                .orElse(0) + 1;
    }

    private static int parseId(String id) {
        try {
            return Integer.parseInt(id);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public enum EvidenceType {
        DOCUMENT_CHUNK,
        SQL_RESULT,
        MEMORY
    }

    public record Evidence(
            String id,
            EvidenceType type,
            String summary,
            String payloadRef,
            Map<String, Object> metadata
    ) {
        public String citationText() {
            return "证据 [" + id + "]：" + summary;
        }
    }
}

package com.agenticrag.rag.index;

import com.agenticrag.rag.dto.Chunk;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.List;

@Component
public class Bm25Store {

    private final JdbcTemplate jdbcTemplate;

    public Bm25Store(@Value("${rag.data-dir:./data}") String dataDir) throws Exception {
        Files.createDirectories(Path.of(dataDir));
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.sqlite.JDBC");
        dataSource.setUrl("jdbc:sqlite:" + Path.of(dataDir, "bm25.db"));
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        ensureSchema();
    }

    public void saveChunks(String docName, List<Chunk> chunks) {
        String sql = """
                INSERT INTO chunks_fts(chunk_id, document_id, doc_name, seq, content, indexed_content)
                VALUES (?, ?, ?, ?, ?, ?)
                """;
        for (Chunk chunk : chunks) {
            jdbcTemplate.update(
                    sql,
                    chunk.id(),
                    chunk.documentId(),
                    docName,
                    chunk.seq(),
                    chunk.content(),
                    normalizeForIndex(chunk.content())
            );
        }
    }

    public List<VectorSearchResult> search(String query, int topK) {
        String sql = """
                SELECT chunk_id, document_id, doc_name, seq, content, bm25(chunks_fts) AS score
                FROM chunks_fts
                WHERE indexed_content MATCH ?
                ORDER BY score
                LIMIT ?
                """;
        return jdbcTemplate.query(sql, (rs, rowNum) -> new VectorSearchResult(
                rs.getLong("chunk_id"),
                rs.getLong("document_id"),
                rs.getInt("seq"),
                rs.getString("content"),
                rs.getString("doc_name"),
                rs.getDouble("score")
        ), toMatchQuery(normalizeForIndex(query)), topK);
    }

    public void deleteByDocumentId(long documentId) {
        jdbcTemplate.update("DELETE FROM chunks_fts WHERE document_id = ?", documentId);
    }

    private void ensureSchema() {
        if (needsRebuild()) {
            jdbcTemplate.execute("DROP TABLE IF EXISTS chunks_fts");
        }
        jdbcTemplate.execute("""
                CREATE VIRTUAL TABLE IF NOT EXISTS chunks_fts USING fts5(
                    chunk_id UNINDEXED,
                    document_id UNINDEXED,
                    doc_name UNINDEXED,
                    seq UNINDEXED,
                    content UNINDEXED,
                    indexed_content,
                    tokenize = 'unicode61'
                )
                """);
    }

    private boolean needsRebuild() {
        List<Map<String, Object>> columns = jdbcTemplate.queryForList("PRAGMA table_xinfo(chunks_fts)");
        if (columns.isEmpty()) {
            return false;
        }
        boolean hasDocumentId = columns.stream().anyMatch(column -> "document_id".equals(column.get("name")));
        boolean hasIndexedContent = columns.stream().anyMatch(column -> "indexed_content".equals(column.get("name")));
        boolean contentIndexed = columns.stream()
                .filter(column -> "content".equals(column.get("name")))
                .findFirst()
                .map(column -> ((Number) column.get("hidden")).intValue() == 0)
                .orElse(false);
        return !hasDocumentId || !hasIndexedContent || contentIndexed;
    }

    /**
     * 统一入库/查询两侧的中文 bigram + 英文数字分词规则。
     * - 英文/数字按连续串成词
     * - ASCII 标点一律视为分隔符
     * - 非 ASCII 字符按二字滑窗切词
     */
    private String normalizeForIndex(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        StringBuilder asciiToken = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isAsciiLetterOrDigit(c)) {
                asciiToken.append(c);
                continue;
            }
            flushAsciiToken(sb, asciiToken);
            if (isAscii(c)) {
                // ASCII 标点/空白作为分隔符，避免 MATCH 把 - : 等解释成操作符
                continue;
            }
            if (i + 1 < text.length() && !isAscii(text.charAt(i + 1))) {
                sb.append(c).append(text.charAt(i + 1)).append(' ');
            } else {
                sb.append(c).append(' ');
            }
        }
        flushAsciiToken(sb, asciiToken);
        return sb.toString().trim();
    }

    private String toMatchQuery(String normalized) {
        if (normalized == null || normalized.isBlank()) {
            return "\"\"";
        }
        StringBuilder query = new StringBuilder();
        for (String token : normalized.split("\\s+")) {
            if (token.isBlank()) {
                continue;
            }
            if (!query.isEmpty()) {
                query.append(' ');
            }
            query.append('"').append(token.replace("\"", "\"\"")).append('"');
        }
        return query.isEmpty() ? "\"\"" : query.toString();
    }

    private void flushAsciiToken(StringBuilder sb, StringBuilder asciiToken) {
        if (asciiToken.isEmpty()) {
            return;
        }
        sb.append(asciiToken).append(' ');
        asciiToken.setLength(0);
    }

    private boolean isAsciiLetterOrDigit(char c) {
        return c < 128 && Character.isLetterOrDigit(c);
    }

    private boolean isAscii(char c) {
        return c < 128;
    }
}

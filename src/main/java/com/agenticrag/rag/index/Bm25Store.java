package com.agenticrag.rag.index;

import com.agenticrag.rag.dto.Chunk;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Component
public class Bm25Store {


    private static final int SCHEMA_VERSION = 2; // v2: indexed_content 生成规则升级（停用词过滤 + Porter 词干 + 特殊 token 紧凑形态），旧索引必须重建
    /** AND 主查询命中数低于该阈值时降级 OR（Phase 2.1） */
    private static final int MIN_AND_HITS = 3;
    private static final Set<String> EXPECTED_COLUMNS = Set.of(
            "chunk_id", "document_id", "doc_name", "seq", "content", "indexed_content");
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
                    TextAnalyzer.buildIndexedContent(chunk.content())
            );
        }
    }

    /**
     * Phase 2.1 两段式检索：先全 AND 精确匹配；命中低于 {@link #MIN_AND_HITS} 时降级 OR，
     * 特殊 token（ID/编号/日期）始终保持 AND 不放宽，仅放宽普通词。
     */
    public List<VectorSearchResult> search(String query, int topK) {
        TextAnalyzer.QueryTokens tokens = TextAnalyzer.buildQueryTokens(query);
        if (tokens.isEmpty()) {
            return List.of();
        }
        List<VectorSearchResult> andResults = match(TextAnalyzer.buildAndQuery(tokens), topK);
        if (andResults.size() >= MIN_AND_HITS) {
            return andResults;
        }
        String orQuery = TextAnalyzer.buildOrQuery(tokens);
        if (!orQuery.isBlank()) {
            List<VectorSearchResult> orResults = match(orQuery, topK);
            if (orResults.size() > andResults.size()) {
                log.debug("BM25 AND 命中 {} 条 < {}，降级 OR：query=[{}]",
                        andResults.size(), MIN_AND_HITS, query);
                return orResults;
            }
        }
        return andResults;
    }

    private List<VectorSearchResult> match(String matchQuery, int topK) {
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
        ), matchQuery, topK);
    }

    public void deleteByDocumentId(long documentId) {
        jdbcTemplate.update("DELETE FROM chunks_fts WHERE document_id = ?", documentId);
    }

    private void ensureSchema() {
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS bm25_meta(schema_version INT NOT NULL)");
        if (!tableExists()) {
            createFtsTable();
            stampSchemaVersion();
            return;
        }
        Integer version = readSchemaVersion();
        boolean layoutOk = columnMatchExpected();
        if (layoutOk && version != null && version == SCHEMA_VERSION) {
            return;
        }
        if (layoutOk && version == null) {
            log.info("检测到既有 BM25 索引（无版本标记），补登为 v{}，保留现有数据", SCHEMA_VERSION);
            stampSchemaVersion();
            return;
        }
        log.warn("BM25 索引 schema 变更（记录版本={}，期望 v{}，布局匹配={}），重建中...", version, SCHEMA_VERSION, layoutOk);
        jdbcTemplate.execute("DROP TABLE IF EXISTS chunks_fts");
        vacuum();
        createFtsTable();
        stampSchemaVersion();
    }

    private void createFtsTable() {
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


    private boolean tableExists() {
        List<String> names = jdbcTemplate.queryForList(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'chunks_fts'", String.class);
        return !names.isEmpty();
    }

    /**
     * 用 PRAGMA table_info 校验既有列集合与当前声明完全一致。
     * FTS5 中所有显式声明列（含 UNINDEXED）的 hidden 恒为 0，无法用 hidden 区分 UNINDEXED，
     * 故改为列集合等值判定。
     */
    private boolean columnMatchExpected() {
        List<Map<String, Object>> columns = jdbcTemplate.queryForList("PRAGMA table_info(chunks_fts)");
        if (columns.size() != EXPECTED_COLUMNS.size()) {
            return false;
        }
        Set<String> actual = new LinkedHashSet<>();
        for (Map<String, Object> column : columns) {
            actual.add(String.valueOf(column.get("name")));
        }
        return actual.containsAll(EXPECTED_COLUMNS);
    }

    private Integer readSchemaVersion() {
        List<Integer> versions = jdbcTemplate.queryForList(
                "SELECT schema_version FROM bm25_meta LIMIT 1", Integer.class);
        return versions.isEmpty() ? null : versions.get(0);
    }

    private void stampSchemaVersion() {
        jdbcTemplate.update("DELETE FROM bm25_meta");
        jdbcTemplate.update("INSERT INTO bm25_meta(schema_version) VALUES (?)", SCHEMA_VERSION);
    }

    /**
     * 当前已索引的 chunk 行数（不含虚拟列语义），供启动健康断言使用。
     */
    public long countChunks() {
        Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM chunks_fts", Long.class);
        return count == null ? 0L : count;
    }

    /** 重建后回收空闲页（此前 silent bug 留下的大 freelist，仅 schema 重建时执行一次） */
    private void vacuum() {
        try {
            jdbcTemplate.execute("VACUUM");
        } catch (Exception e) {
            log.warn("BM25 数据库 VACUUM 失败（不影响功能）: {}", e.getMessage());
        }
    }
}

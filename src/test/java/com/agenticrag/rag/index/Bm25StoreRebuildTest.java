package com.agenticrag.rag.index;

import com.agenticrag.rag.dto.Chunk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M6 Phase 1 回归测试（Phase 2 适配版）：ensureSchema 双判据决策表 + AND→OR 降级验证。
 * SCHEMA_VERSION=2 后 legacy 无版本标记老库被补登为 v2（对齐当前版本），schema 断言从 1→2。
 */
class Bm25StoreRebuildTest {

    @TempDir
    Path tempDir;

    @Test
    void reopenWithSameDataDirKeepsExistingIndex() throws Exception {
        Bm25Store first = new Bm25Store(tempDir.toString());
        saveTwoChunks(first);
        assertEquals(2, first.countChunks());

        Bm25Store second = new Bm25Store(tempDir.toString());
        assertEquals(2, second.countChunks());
        assertTrue(second.search("你好世界", 3).stream().anyMatch(hit -> hit.chunkId() == 1L));
    }

    @Test
    void schemaVersionDriftTriggersRebuild() throws Exception {
        saveTwoChunks(new Bm25Store(tempDir.toString()));
        rawJdbc().update("UPDATE bm25_meta SET schema_version = 999");

        Bm25Store rebuilt = new Bm25Store(tempDir.toString());
        assertEquals(0, rebuilt.countChunks());
    }

    @Test
    void legacyLayoutWithoutStampGetsAdopted() throws Exception {
        JdbcTemplate raw = rawJdbc();
        raw.execute("""
                CREATE VIRTUAL TABLE chunks_fts USING fts5(
                    chunk_id UNINDEXED,
                    document_id UNINDEXED,
                    doc_name UNINDEXED,
                    seq UNINDEXED,
                    content UNINDEXED,
                    indexed_content,
                    tokenize = 'unicode61'
                )
                """);
        raw.update(
                "INSERT INTO chunks_fts(chunk_id, document_id, doc_name, seq, content, indexed_content) VALUES (?, ?, ?, ?, ?, ?)",
                5L, 50L, "legacy.md", 1, "存量老库数据", "存量 老库 数据");

        Bm25Store adopted = new Bm25Store(tempDir.toString());

        assertEquals(1, adopted.countChunks());
        // v2 阶段 legacy 库补登为 v2（以前是 v1）
        assertEquals(2, rawJdbc().queryForObject("SELECT schema_version FROM bm25_meta", Integer.class));
    }

    // ==================== Phase 2.1：AND→OR 降级 ====================

    @Test
    void andToOrDegradationWithStopWordFiltered() throws Exception {
        Bm25Store store = new Bm25Store(tempDir.toString());
        // 入库 token：索引中只有 "produc"（production→produc via Porter）、"deploy"、"run"
        store.saveChunks("ops", List.of(
                new Chunk(1L, 10L, 1, "The production deployment process is documented here."),
                new Chunk(2L, 11L, 1, "Running services in production requires careful monitoring.")
        ));

        // 查询 "the production deployment" → tokens=["produc","deploy"]（"the"停用词过滤）
        // AND → 两个词都要命中，只有 chunk1 命中 → 1 条 < MIN_AND_HITS=3，触发降级 OR
        // OR → chunk1 (produc+deploy) + chunk2 (produc) → 2 条
        List<VectorSearchResult> results = store.search("the production deployment", 5);
        assertEquals(2, results.size());
    }

    @Test
    void andQueryWithEnoughHitsNoDegradation() throws Exception {
        Bm25Store store = new Bm25Store(tempDir.toString());
        // 入库 4 个 chunk，都含 "deploy" → AND "deploy servic" 至少命中 3 条，不降级
        store.saveChunks("ops", List.of(
                new Chunk(1L, 10L, 1, "deploy service alpha"),
                new Chunk(2L, 10L, 2, "deploy service beta"),
                new Chunk(3L, 10L, 3, "deploy service gamma"),
                new Chunk(4L, 11L, 1, "monitoring dashboard setup")
        ));
        // "the deploy service" → tokens=["deploy","servic"] ("the" 停用词，service→servic)
        // AND → chunk1/2/3 都含 deploy+service → ≥3
        List<VectorSearchResult> results = store.search("the deploy service", 5);
        assertEquals(3, results.size());
    }

    private void saveTwoChunks(Bm25Store store) {
        store.saveChunks("中文文档", List.of(
                new Chunk(1L, 10L, 1, "你好世界，RAG 检索很稳定。"),
                new Chunk(2L, 11L, 1, "今天天气不错，适合出去散步。")
        ));
    }

    private JdbcTemplate rawJdbc() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.sqlite.JDBC");
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("bm25.db"));
        return new JdbcTemplate(dataSource);
    }
}
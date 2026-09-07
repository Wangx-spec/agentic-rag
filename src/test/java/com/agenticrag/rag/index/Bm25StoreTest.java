package com.agenticrag.rag.index;

import com.agenticrag.rag.dto.Chunk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Bm25StoreTest {

    @TempDir
    Path tempDir;

    @Test
    void searchFindsChineseContentByBigram() throws Exception {
        Bm25Store store = new Bm25Store(tempDir.toString());
        store.saveChunks("中文文档", List.of(
                new Chunk(1L, 10L, 1, "你好世界，RAG 检索很稳定。"),
                new Chunk(2L, 11L, 1, "今天天气不错，适合出去散步。")
        ));

        List<VectorSearchResult> results = store.search("你好世界", 3);

        assertEquals(1, results.get(0).chunkId());
        assertEquals("中文文档", results.get(0).docName());
        assertTrue(results.get(0).content().contains("你好世界"));
    }
}

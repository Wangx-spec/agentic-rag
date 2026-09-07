package com.agenticrag.rag.index;

import com.agenticrag.rag.dto.Chunk;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryVectorStoreTest {

    @Test
    void searchReturnsMostSimilarChunkAndDeleteRemovesIt() {
        InMemoryVectorStore store = new InMemoryVectorStore();
        store.saveChunks(10L, "demo.md", List.of(
                new Chunk(1L, 10L, 1, "chunk one"),
                new Chunk(2L, 10L, 2, "chunk two")
        ), List.of(
                new float[]{1.0f, 0.0f},
                new float[]{0.0f, 1.0f}
        ));

        List<VectorSearchResult> results = store.searchByVector(new float[]{0.9f, 0.1f}, 2);
        assertEquals(1L, results.get(0).chunkId());

        store.deleteByDocumentId(10L);

        assertTrue(store.searchByVector(new float[]{0.9f, 0.1f}, 2).isEmpty());
    }
}

package com.agenticrag.rag.index;

import com.agenticrag.config.RagProperties;
import com.agenticrag.rag.dto.Chunk;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.Socket;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QdrantStoreIntegrationTest {

    @Mock
    private EmbeddingClient embeddingClient;

    @Test
    void saveSearchAndDeleteWorkAgainstRunningQdrant() {
        Assumptions.assumeTrue(isPortOpen("localhost", 6334), "本地未启动 Qdrant，跳过集成测试");

        RagProperties properties = new RagProperties();
        properties.setEmbeddingDim(2);
        properties.getVector().getQdrant().setHost("localhost");
        properties.getVector().getQdrant().setPort(6334);
        properties.getVector().getQdrant().setCollection("agentic_rag_chunks_it_" + System.nanoTime());

        when(embeddingClient.modelSlug()).thenReturn("test_model");

        QdrantStore store = new QdrantStore(properties, embeddingClient);
        store.init();

        List<Chunk> chunks = List.of(
                new Chunk(1L, 100L, 1, "vector one"),
                new Chunk(2L, 100L, 2, "vector two")
        );
        store.saveChunks(100L, "demo.pdf", chunks, List.of(
                new float[]{1.0f, 0.0f},
                new float[]{0.0f, 1.0f}
        ));

        List<VectorSearchResult> results = store.searchByVector(new float[]{0.9f, 0.1f}, 2);
        assertEquals(1L, results.get(0).chunkId());
        assertEquals("demo.pdf", results.get(0).docName());

        store.deleteByDocumentId(100L);
        assertTrue(store.searchByVector(new float[]{0.9f, 0.1f}, 2).isEmpty());
    }

    private boolean isPortOpen(String host, int port) {
        try (Socket ignored = new Socket(host, port)) {
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}

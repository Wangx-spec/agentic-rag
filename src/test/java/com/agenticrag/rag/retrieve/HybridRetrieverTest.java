package com.agenticrag.rag.retrieve;

import com.agenticrag.config.RagProperties;
import com.agenticrag.rag.index.Bm25Store;
import com.agenticrag.rag.index.EmbeddingClient;
import com.agenticrag.rag.index.VectorSearchResult;
import com.agenticrag.rag.index.VectorStore;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HybridRetrieverTest {

    @Test
    void retrieveRanksByRrfAndRenumbersFinalResults() {
        EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
        VectorStore vectorStore = mock(VectorStore.class);
        Bm25Store bm25Store = mock(Bm25Store.class);

        RagProperties ragProperties = new RagProperties();
        ragProperties.setTopK(10);
        ragProperties.setFinalTopN(3);
        ragProperties.setRrfK(60);

        when(embeddingClient.embed("RAG 是什么")).thenReturn(new float[]{0.1f, 0.2f});
        when(vectorStore.searchByVector(any(float[].class), eq(10))).thenReturn(List.of(
                new VectorSearchResult(1L, 100L, 1, "向量第一", "A.pdf", 0.95),
                new VectorSearchResult(2L, 100L, 2, "双通道命中", "A.pdf", 0.90)
        ));
        when(bm25Store.search("RAG 是什么", 10)).thenReturn(List.of(
                new VectorSearchResult(2L, 100L, 2, "双通道命中", "A.pdf", -2.0),
                new VectorSearchResult(3L, 101L, 1, "BM25 第二", "B.pdf", -1.0)
        ));

        HybridRetriever retriever = new HybridRetriever(embeddingClient, vectorStore, bm25Store, ragProperties);

        List<RetrievedChunk> results = retriever.retrieve("RAG 是什么");

        assertEquals(List.of(2L, 1L, 3L), results.stream().map(RetrievedChunk::chunkId).toList());
        assertEquals(List.of(1, 2, 3), results.stream().map(RetrievedChunk::rank).toList());
    }
}

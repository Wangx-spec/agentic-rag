package com.agenticrag.tool.tools;

import com.agenticrag.config.RagProperties;
import com.agenticrag.rag.index.Bm25Store;
import com.agenticrag.rag.index.EmbeddingClient;
import com.agenticrag.rag.index.VectorSearchResult;
import com.agenticrag.rag.index.VectorStore;
import com.agenticrag.rag.retrieve.HybridRetriever;
import com.agenticrag.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BuiltInToolsTest {

    @Test
    void searchKnowledgeBaseReturnsFormattedChunks() {
        EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
        VectorStore vectorStore = mock(VectorStore.class);
        Bm25Store bm25Store = mock(Bm25Store.class);
        RagProperties ragProperties = new RagProperties();
        ragProperties.setTopK(2);
        ragProperties.setFinalTopN(2);
        ragProperties.setRrfK(60);

        when(embeddingClient.embed("M3 状态机")).thenReturn(new float[]{0.1f, 0.2f});
        when(vectorStore.searchByVector(any(float[].class), anyInt())).thenReturn(List.of(
                new VectorSearchResult(1L, 1L, 1, "THINKING→ACTING→OBSERVING→FINAL", "plan.pdf", 0.95)
        ));
        when(bm25Store.search("M3 状态机", 2)).thenReturn(List.of(
                new VectorSearchResult(1L, 1L, 1, "THINKING→ACTING→OBSERVING→FINAL", "plan.pdf", -1.0)
        ));

        HybridRetriever hybridRetriever = new HybridRetriever(embeddingClient, vectorStore, bm25Store, ragProperties);
        ToolRegistry toolRegistry = new ToolRegistry();
        SearchKnowledgeBaseTool tool = new SearchKnowledgeBaseTool(hybridRetriever, toolRegistry);
        tool.register();

        String result = tool.execute(Map.of("query", "M3 状态机"));

        assertTrue(result.contains("检索到"));
        assertTrue(result.contains("[1]"));
        assertTrue(result.contains("plan.pdf"));
        assertTrue(result.contains("THINKING→ACTING→OBSERVING→FINAL"));
    }

    @Test
    void calculatorEvaluatesFourOperationsAndParentheses() {
        CalculatorTool tool = new CalculatorTool(new ToolRegistry());
        tool.register();

        assertEquals("1086", tool.execute(Map.of("expression", "23*47+5")));
        assertEquals("4", tool.execute(Map.of("expression", "(1+3)*2/2")));
        assertEquals("6", tool.execute(Map.of("expression", "10-4")));
    }
}
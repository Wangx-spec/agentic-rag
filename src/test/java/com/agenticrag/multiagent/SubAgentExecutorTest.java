package com.agenticrag.multiagent;

import com.agenticrag.llm.LlmClient;
import com.agenticrag.multiagent.dto.SubTaskResult;
import com.agenticrag.rag.retrieve.HybridRetriever;
import com.agenticrag.rag.retrieve.RetrievedChunk;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SubAgentExecutorTest {

    private final HybridRetriever hybridRetriever = mock(HybridRetriever.class);
    private final LlmClient llmClient = mock(LlmClient.class);
    private final SubAgentExecutor executor = new SubAgentExecutor(hybridRetriever, llmClient);

    @Test
    void executeReturnsSuccessWhenRetrieverAndLlmSucceed() {
        List<RetrievedChunk> chunks = List.of(
                new RetrievedChunk(1L, 10L, 1, "A 是检索增强生成。", "guide.md", 0.9, 1)
        );
        when(hybridRetriever.retrieve("A 是什么？")).thenReturn(chunks);
        when(llmClient.chat(anyList())).thenReturn("A 是检索增强生成 [1]");

        SubTaskResult result = executor.execute("A 是什么？", 1);

        assertTrue(result.success());
        assertEquals("A 是检索增强生成 [1]", result.conclusion());
        assertEquals(chunks, result.sources());
    }

    @Test
    void executeReturnsFailureWhenRetrieverReturnsEmpty() {
        when(hybridRetriever.retrieve("A 是什么？")).thenReturn(List.of());

        SubTaskResult result = executor.execute("A 是什么？", 1);

        assertFalse(result.success());
        assertEquals("检索无结果", result.error());
    }

    @Test
    void executeReturnsFailureWhenRetrieverThrows() {
        when(hybridRetriever.retrieve("A 是什么？")).thenThrow(new RuntimeException("retriever down"));

        SubTaskResult result = executor.execute("A 是什么？", 1);

        assertFalse(result.success());
        assertEquals("retriever down", result.error());
    }
}

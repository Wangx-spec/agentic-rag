package com.agenticrag.intent;

import com.agenticrag.config.RagProperties;
import com.agenticrag.llm.LlmClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class QueryUnderstandingServiceTest {

    private LlmClient llmClient;
    private IntentProperties intentProperties;
    private RagProperties ragProperties;
    private QueryUnderstandingService service;

    @BeforeEach
    void setUp() {
        llmClient = mock(LlmClient.class);
        intentProperties = new IntentProperties();
        intentProperties.setConfidenceThreshold(0.6);
        ragProperties = new RagProperties();
        service = new QueryUnderstandingService(llmClient, intentProperties, ragProperties);
    }

    @Test
    void understandsKbQuestion() {
        when(llmClient.chat(org.mockito.ArgumentMatchers.anyList()))
                .thenReturn("""
                        {"intent":"KB_QA","confidence":0.92,
                         "normalized_query":"RAG 检索增强生成的定义和工作原理","sub_queries":[]}
                        """);

        Optional<QueryUnderstanding> result = service.understand("RAG 是什么？");

        assertTrue(result.isPresent());
        assertEquals(Intent.KB_QA, result.get().intent());
        assertEquals("RAG 检索增强生成的定义和工作原理", result.get().normalizedQuery());
    }

    @Test
    void understandsMultiTaskWithSubQueries() {
        when(llmClient.chat(org.mockito.ArgumentMatchers.anyList()))
                .thenReturn("""
                        ```json
                        {"intent":"MULTI_TASK","confidence":0.9,
                         "normalized_query":"比较 A 和 B 的定义、差异及适用场景",
                         "sub_queries":["A 的定义是什么？","B 的定义是什么？","A 和 B 有什么差异？"]}
                        ```
                        """);

        QueryUnderstanding result = service.understand("请介绍 A、B 并比较区别").orElseThrow();

        assertEquals(Intent.MULTI_TASK, result.intent());
        assertEquals(3, result.subQueries().size());
    }

    @Test
    void invalidJsonFailsOpen() {
        when(llmClient.chat(org.mockito.ArgumentMatchers.anyList())).thenReturn("{bad");

        assertTrue(service.understand("问题").isEmpty());
    }

    @Test
    void unknownIntentFailsOpen() {
        when(llmClient.chat(org.mockito.ArgumentMatchers.anyList()))
                .thenReturn("{\"intent\":\"NOT_A_REAL_INTENT\",\"confidence\":0.9}");

        assertTrue(service.understand("问题").isEmpty());
    }

    @Test
    void llmExceptionFailsOpen() {
        when(llmClient.chat(org.mockito.ArgumentMatchers.anyList()))
                .thenThrow(new RuntimeException("timeout"));

        assertTrue(service.understand("问题").isEmpty());
    }

    @Test
    void disabledSkipsLlmCall() {
        ragProperties.getAgent().setQueryUnderstandingEnabled(false);

        assertTrue(service.understand("问题").isEmpty());
        verifyNoInteractions(llmClient);
    }

    @Test
    void lowConfidenceFailsOpen() {
        when(llmClient.chat(org.mockito.ArgumentMatchers.anyList()))
                .thenReturn("{\"intent\":\"KB_QA\",\"confidence\":0.2}");

        assertTrue(service.understand("问题").isEmpty());
    }
}

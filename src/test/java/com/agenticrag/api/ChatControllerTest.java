package com.agenticrag.api;

import com.agenticrag.config.LlmProperties;
import com.agenticrag.llm.LlmClient;
import com.agenticrag.llm.dto.ChatMessage;
import com.agenticrag.memory.ConversationMemory;
import com.agenticrag.rag.retrieve.HybridRetriever;
import com.agenticrag.rag.retrieve.RetrievedChunk;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ChatController.class)
class ChatControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private LlmProperties llmProperties;

    @MockBean
    private LlmClient llmClient;

    @MockBean
    private ConversationMemory memory;

    @MockBean
    private HybridRetriever hybridRetriever;

    @Test
    void chatStreamsAnswerAndSourcesWhenRetrieverReturnsChunks() throws Exception {
        when(llmProperties.isConfigured()).thenReturn(true);
        when(llmProperties.getMemoryRounds()).thenReturn(5);
        doNothing().when(memory).append(anyString(), any(ChatMessage.class));
        when(memory.load(anyString(), anyInt())).thenReturn(List.of(ChatMessage.user("什么是 RAG？")));
        when(hybridRetriever.retrieve("什么是 RAG？")).thenReturn(List.of(
                new RetrievedChunk(1L, 10L, 1, "RAG uses vector retrieval.", "guide.pdf", 1.0, 1)
        ));
        when(llmClient.chatStream(anyList(), any())).thenAnswer(invocation -> {
            LlmClient.StreamListener listener = invocation.getArgument(1);
            listener.onThinking("先检索知识库，再组织答案。");
            listener.onAnswer("RAG answers with citations [1].");
            return "RAG answers with citations [1].";
        });

        MvcResult result = mockMvc.perform(post("/api/chat")
                        .contentType("application/json")
                        .content("""
                                {"sessionId":"s1","message":"什么是 RAG？"}
                                """))
                .andExpect(request().asyncStarted())
                .andReturn();

        String body = mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        org.junit.jupiter.api.Assertions.assertTrue(body.contains("event:thinking"));
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("\"text\":"));
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("RAG answers with citations [1]."));
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("guide.pdf"));
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("\"n\":1"));
    }
}

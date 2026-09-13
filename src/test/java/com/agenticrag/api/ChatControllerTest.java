package com.agenticrag.api;

import com.agenticrag.agent.AgentLoop;
import com.agenticrag.config.LlmProperties;
import com.agenticrag.intent.Intent;
import com.agenticrag.intent.IntentClassifier;
import com.agenticrag.llm.LlmClient;
import com.agenticrag.llm.dto.ChatMessage;
import com.agenticrag.memory.ConversationMemory;
import com.agenticrag.multiagent.MultiAgentOrchestrator;
import com.agenticrag.rag.retrieve.HybridRetriever;
import com.agenticrag.rag.retrieve.RetrievedChunk;
import com.agenticrag.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

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

    @MockBean
    private AgentLoop agentLoop;

    @MockBean
    private ToolRegistry toolRegistry;

    @MockBean
    private IntentClassifier intentClassifier;

    @MockBean
    private MultiAgentOrchestrator multiAgentOrchestrator;

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
                                {"sessionId":"s1","message":"什么是 RAG？","mode":"rag"}
                                """))
                .andExpect(request().asyncStarted())
                .andReturn();

        result.getAsyncResult(3000);

        String body = mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        org.junit.jupiter.api.Assertions.assertTrue(body.contains("event:thinking"));
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("\"text\":"));
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("RAG answers with citations [1]."));
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("guide.pdf"));
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("\"n\":1"));
    }

    @Test
    void chatDefaultsToAgentModeWhenLegacyFlagsMissing() throws Exception {
        when(llmProperties.isConfigured()).thenReturn(true);
        when(llmProperties.getMemoryRounds()).thenReturn(5);
        when(llmProperties.getMaxAgentRounds()).thenReturn(5);
        when(toolRegistry.all()).thenReturn(Map.of());
        doNothing().when(memory).append(anyString(), any(ChatMessage.class));
        when(memory.load(anyString(), anyInt())).thenReturn(List.of(ChatMessage.user("查一下 M3")));
        when(agentLoop.run(any(), any())).thenReturn("这是 Agent 模式回答。");

        MvcResult result = mockMvc.perform(post("/api/chat")
                        .contentType("application/json")
                        .content("""
                                {"sessionId":"s1","message":"查一下 M3"}
                                """))
                .andExpect(request().asyncStarted())
                .andReturn();

        result.getAsyncResult(3000);

        String body = mockMvc.perform(asyncDispatch(result)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("event:delta"));
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("event:done"));
        verify(agentLoop).run(any(), any());
    }

    @Test
    void chatFallsBackToRagWhenAgentFails() throws Exception {
        when(llmProperties.isConfigured()).thenReturn(true);
        when(llmProperties.getMemoryRounds()).thenReturn(5);
        when(llmProperties.getMaxAgentRounds()).thenReturn(5);
        when(toolRegistry.all()).thenReturn(Map.of());
        doNothing().when(memory).append(anyString(), any(ChatMessage.class));
        when(memory.load(anyString(), anyInt())).thenReturn(List.of(ChatMessage.user("查一下状态机说明")));
        when(agentLoop.run(any(), any())).thenThrow(new RuntimeException("agent down"));
        when(hybridRetriever.retrieve("查一下状态机说明")).thenReturn(List.of(
                new RetrievedChunk(1L, 10L, 1, "状态机包含四个状态。", "guide.pdf", 1.0, 1)
        ));
        when(llmClient.chatStream(anyList(), any())).thenAnswer(invocation -> {
            LlmClient.StreamListener listener = invocation.getArgument(1);
            listener.onAnswer("状态机分为四个状态 [1].");
            return "状态机分为四个状态 [1].";
        });

        MvcResult result = mockMvc.perform(post("/api/chat")
                        .contentType("application/json")
                        .content("""
                                {"sessionId":"s1","message":"查一下状态机说明","mode":"agent"}
                                """))
                .andExpect(request().asyncStarted())
                .andReturn();

        result.getAsyncResult(3000);

        String body = mockMvc.perform(asyncDispatch(result)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("Agent 链路异常"));
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("状态机分为四个状态 [1]."));
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("guide.pdf"));
    }

    @Test
    void clearMemoryDeletesSessionHistory() throws Exception {
        mockMvc.perform(delete("/api/memory/s1"))
                .andExpect(status().isNoContent());

        verify(memory).clear("s1");
    }

    @Test
    void chatReturnsFallbackAnswerWhenRagAlsoFails() throws Exception {
        when(llmProperties.isConfigured()).thenReturn(true);
        when(llmProperties.getMemoryRounds()).thenReturn(5);
        when(llmProperties.getMaxAgentRounds()).thenReturn(5);
        when(toolRegistry.all()).thenReturn(Map.of());
        doNothing().when(memory).append(anyString(), any(ChatMessage.class));
        when(memory.load(anyString(), anyInt())).thenReturn(List.of(ChatMessage.user("查一下文档里关于状态机的说明")));
        when(agentLoop.run(any(), any())).thenThrow(new RuntimeException("agent down"));
        when(hybridRetriever.retrieve("查一下文档里关于状态机的说明")).thenThrow(new RuntimeException("rag down"));

        MvcResult result = mockMvc.perform(post("/api/chat")
                        .contentType("application/json")
                        .content("""
                                {"sessionId":"s1","message":"查一下文档里关于状态机的说明","mode":"agent"}
                                """))
                .andExpect(request().asyncStarted())
                .andReturn();

        result.getAsyncResult(3000);

        String body = mockMvc.perform(asyncDispatch(result)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("event:thinking"));
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("event:done"));
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("\"sources\":[]"));
    }

    @Test
    void chatSupportsExplicitMultiAgentMode() throws Exception {
        when(llmProperties.isConfigured()).thenReturn(true);
        doNothing().when(memory).append(anyString(), any(ChatMessage.class));
        when(multiAgentOrchestrator.orchestrate(anyString(), any(SseEmitter.class), anyString())).thenAnswer(invocation -> {
            SseEmitter emitter = invocation.getArgument(1);
            emitter.send(SseEmitter.event().name("thinking").data(Map.of("text", "[多Agent] 开始汇总回答")));
            emitter.send(SseEmitter.event().name("delta").data(Map.of("text", "多 Agent 回答 [1]")));
            emitter.send(SseEmitter.event().name("done").data(Map.of("sources", List.of(
                    Map.of("n", 1, "docName", "guide.pdf", "snippet", "片段")
            ))));
            emitter.complete();
            return true;
        });

        MvcResult result = mockMvc.perform(post("/api/chat")
                        .contentType("application/json")
                        .content("""
                                {"sessionId":"s1","message":"A 是什么？A 和 B 有什么区别？","mode":"multi-agent"}
                                """))
                .andExpect(request().asyncStarted())
                .andReturn();

        result.getAsyncResult(3000);

        String body = mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        org.junit.jupiter.api.Assertions.assertTrue(body.contains("[多Agent] 开始汇总回答"));
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("多 Agent 回答 [1]"));
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("guide.pdf"));
    }

    @Test
    void autoModeRoutesMultiTaskToMultiAgent() throws Exception {
        when(llmProperties.isConfigured()).thenReturn(true);
        doNothing().when(memory).append(anyString(), any(ChatMessage.class));
        when(intentClassifier.classify("A 是什么？A 和 B 有什么区别？"))
                .thenReturn(new IntentClassifier.IntentResult(Intent.MULTI_TASK, 0.95));
        when(multiAgentOrchestrator.orchestrate(anyString(), any(SseEmitter.class), anyString())).thenAnswer(invocation -> {
            SseEmitter emitter = invocation.getArgument(1);
            emitter.send(SseEmitter.event().name("delta").data(Map.of("text", "auto 多 Agent 回答")));
            emitter.send(SseEmitter.event().name("done").data(Map.of("sources", List.of())));
            emitter.complete();
            return true;
        });

        MvcResult result = mockMvc.perform(post("/api/chat")
                        .contentType("application/json")
                        .content("""
                                {"sessionId":"s1","message":"A 是什么？A 和 B 有什么区别？","mode":"auto"}
                                """))
                .andExpect(request().asyncStarted())
                .andReturn();

        result.getAsyncResult(3000);

        String body = mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        org.junit.jupiter.api.Assertions.assertTrue(body.contains("auto 多 Agent 回答"));
        verify(multiAgentOrchestrator).orchestrate(eq("A 是什么？A 和 B 有什么区别？"), any(SseEmitter.class), eq("s1"));
    }
}

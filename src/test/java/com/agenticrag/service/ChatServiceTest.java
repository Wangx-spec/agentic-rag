package com.agenticrag.service;

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
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatServiceTest {

    @Mock
    private LlmClient llmClient;
    @Mock
    private ConversationMemory memory;
    @Mock
    private HybridRetriever hybridRetriever;
    @Mock
    private AgentLoop agentLoop;
    @Mock
    private ToolRegistry toolRegistry;
    @Mock
    private IntentClassifier intentClassifier;
    @Mock
    private MultiAgentOrchestrator multiAgentOrchestrator;

    private ChatService newService() {
        LlmProperties properties = new LlmProperties();
        properties.setApiKey("test");
        properties.setBaseUrl("https://example.com/v1");
        properties.setChatModel("demo");
        properties.setEmbeddingModel("embed-demo");
        properties.setMemoryRounds(5);
        properties.setMaxAgentRounds(5);
        return new ChatService(properties, llmClient, memory, hybridRetriever, agentLoop, toolRegistry, intentClassifier, multiAgentOrchestrator);
    }

    @Test
    void ragModeStreamsAnswerAndSources() {
        ChatService service = newService();
        List<String> events = new ArrayList<>();
        doNothing().when(memory).append(anyString(), any(ChatMessage.class));
        when(memory.load(anyString(), anyInt())).thenReturn(List.of(ChatMessage.user("什么是 RAG？")));
        when(hybridRetriever.retrieve("什么是 RAG？")).thenReturn(List.of(
                new RetrievedChunk(1L, 10L, 1, "RAG uses vector retrieval.", "guide.pdf", 1.0, 1)
        ));
        when(llmClient.chatStream(anyList(), any())).thenAnswer(invocation -> {
            LlmClient.StreamListener listener = invocation.getArgument(1);
            listener.onThinking("先检索知识库");
            listener.onAnswer("RAG answers with citations [1].");
            return "RAG answers with citations [1].";
        });

        ChatResult result = service.chat("s1", "什么是 RAG？", ChatMode.RAG, sink(events));

        assertEquals(ChatMode.RAG, result.executedMode());
        assertEquals("RAG answers with citations [1].", result.answer());
        assertEquals(1, result.sources().size());
        assertTrue(events.stream().anyMatch(e -> e.contains("thinking:先检索知识库")));
        assertTrue(events.stream().anyMatch(e -> e.contains("delta:RAG answers with citations [1].")));
    }

    @Test
    void agentModeFallsBackToRagWhenAgentFails() {
        ChatService service = newService();
        List<String> events = new ArrayList<>();
        doNothing().when(memory).append(anyString(), any(ChatMessage.class));
        when(memory.load(anyString(), anyInt())).thenReturn(List.of(ChatMessage.user("查一下状态机说明")));
        when(toolRegistry.all()).thenReturn(Map.of());
        when(agentLoop.run(any(), any())).thenThrow(new RuntimeException("agent down"));
        when(hybridRetriever.retrieve("查一下状态机说明")).thenReturn(List.of(
                new RetrievedChunk(1L, 10L, 1, "状态机包含四个状态。", "guide.pdf", 1.0, 1)
        ));
        when(llmClient.chatStream(anyList(), any())).thenAnswer(invocation -> {
            LlmClient.StreamListener listener = invocation.getArgument(1);
            listener.onAnswer("状态机分为四个状态 [1].");
            return "状态机分为四个状态 [1].";
        });

        ChatResult result = service.chat("s1", "查一下状态机说明", ChatMode.AGENT, sink(events));

        assertEquals(ChatMode.RAG, result.executedMode());
        assertTrue(result.degraded());
        assertTrue(events.stream().anyMatch(e -> e.contains("Agent 链路异常")));
        assertEquals("状态机分为四个状态 [1].", result.answer());
    }

    @Test
    void autoModeRoutesMultiTaskToMultiAgent() {
        ChatService service = newService();
        List<String> events = new ArrayList<>();
        doNothing().when(memory).append(anyString(), any(ChatMessage.class));
        when(intentClassifier.classify("A 是什么？A 和 B 有什么区别？"))
                .thenReturn(new IntentClassifier.IntentResult(Intent.MULTI_TASK, 0.95));
        when(multiAgentOrchestrator.orchestrate(eq("A 是什么？A 和 B 有什么区别？"), any(ChatEventSink.class), eq("s1")))
                .thenAnswer(invocation -> {
                    ChatEventSink sink = invocation.getArgument(1);
                    sink.onThinking("[多Agent] 开始汇总回答");
                    sink.onDelta("auto 多 Agent 回答");
                    sink.onDone(List.of(new RetrievedChunk(1L, 10L, 1, "片段", "guide.pdf", 1.0, 1)));
                    return true;
                });

        ChatResult result = service.chat("s1", "A 是什么？A 和 B 有什么区别？", ChatMode.AUTO, sink(events));

        assertEquals(ChatMode.MULTI_AGENT, result.executedMode());
        assertEquals(Intent.MULTI_TASK, result.routedIntent());
        assertEquals("auto 多 Agent 回答", result.answer());
        assertTrue(events.stream().anyMatch(e -> e.contains("[多Agent] 开始汇总回答")));
    }

    @Test
    void clearMemoryDelegatesToMemoryStore() {
        ChatService service = newService();

        service.clearMemory("s1");

        verify(memory).clear("s1");
    }

    private ChatEventSink sink(List<String> events) {
        return new ChatEventSink() {
            @Override
            public void onThinking(String text) {
                events.add("thinking:" + text);
            }

            @Override
            public void onDelta(String text) {
                events.add("delta:" + text);
            }

            @Override
            public void onDone(List<RetrievedChunk> sources) {
                events.add("done:" + sources.size());
            }
        };
    }
}

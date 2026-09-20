package com.agenticrag.agent;

import com.agenticrag.config.LlmProperties;
import com.agenticrag.config.RagProperties;
import com.agenticrag.llm.LlmClient;
import com.agenticrag.llm.dto.ChatMessage;
import com.agenticrag.llm.dto.LlmResponse;
import com.agenticrag.llm.dto.ToolCall;
import com.agenticrag.rag.retrieve.RetrievedChunk;
import com.agenticrag.tool.Tool;
import com.agenticrag.tool.ToolRegistry;
import com.agenticrag.tool.ToolSchemaValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentLoopTest {

    private final ToolSchemaValidator toolSchemaValidator = new ToolSchemaValidator();

    @Mock
    private LlmClient llmClient;
    @Mock
    private ToolRegistry toolRegistry;
    @Mock
    private StepReporter reporter;

    private static LlmProperties properties() {
        LlmProperties p = new LlmProperties();
        p.setMaxAgentRounds(5);
        return p;
    }

    private static RagProperties ragProperties() {
        return new RagProperties();
    }

    @Test
    void noToolNeededSingleRoundFinal() {
        AgentLoop loop = new AgentLoop(llmClient, toolRegistry, properties(), toolSchemaValidator, ragProperties(), null);

        when(llmClient.chatWithTools(anyList(), anyList()))
                .thenReturn(new LlmResponse("今天天气晴，适合出门。", List.of()));

        AgentContext ctx = new AgentContext(
                List.of(ChatMessage.user("今天天气怎么样")),
                List.of(),
                5
        );

        String result = loop.run(ctx, reporter);

        assertEquals("今天天气晴，适合出门。", result);
        assertEquals(List.of("FINAL"), ctx.getStateTrajectory());
        verify(reporter).onFinal("生成最终回答");
    }

    @Test
    void twoRoundConvergeToFinal() {
        Tool mockTool = new Tool() {
            @Override
            public String name() { return "search"; }
            @Override
            public String description() { return "搜索知识库"; }
            @Override
            public String parametersSchema() {
                return "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"}},\"required\":[\"query\"]}";
            }
            @Override
            public String execute(Map<String, Object> args) {
                return "检索到 3 条相关片段，内容涉及 M3 Agent 状态机设计。";
            }
        };

        when(toolRegistry.find("search")).thenReturn(Optional.of(mockTool));

        when(llmClient.chatWithTools(anyList(), anyList()))
                .thenReturn(new LlmResponse("", List.of(
                        new ToolCall("call_1", "search", "{\"query\":\"M3 状态机\"}")
                )))
                .thenReturn(new LlmResponse("M3 状态机方案包含 THINKING→ACTING→OBSERVING→FINAL 四个状态。", List.of()));

        AgentLoop loop = new AgentLoop(llmClient, toolRegistry, properties(), toolSchemaValidator, ragProperties(), null);

        AgentContext ctx = new AgentContext(
                List.of(ChatMessage.user("查一下 M3 状态机方案")),
                List.of(),
                5
        );

        String result = loop.run(ctx, reporter);

        assertTrue(result.contains("FINAL"));
        assertEquals(
                List.of("THINKING", "ACTING", "OBSERVING", "FINAL"),
                ctx.getStateTrajectory()
        );
        assertEquals(4, ctx.getMessages().size());
        verify(reporter).onThinking("search");
        verify(reporter).onActing("search", "{\"query\":\"M3 状态机\"}");
        verify(reporter).onObserving(any());
        verify(reporter).onFinal("生成最终回答");
    }

    @Test
    void maxRoundsReachedForceFinal() {
        Tool mockTool = new Tool() {
            @Override
            public String name() { return "search"; }
            @Override
            public String description() { return "搜索"; }
            @Override
            public String parametersSchema() { return "{}"; }
            @Override
            public String execute(Map<String, Object> args) { return "result"; }
        };

        when(toolRegistry.find("search")).thenReturn(Optional.of(mockTool));
        when(llmClient.chatWithTools(anyList(), anyList()))
                .thenReturn(new LlmResponse("", List.of(
                        new ToolCall("call_1", "search", "{}")
                )));
        when(llmClient.chat(anyList()))
                .thenReturn("基于已有信息，答案是 1086。");

        LlmProperties props = properties();
        props.setMaxAgentRounds(1);

        AgentLoop loop = new AgentLoop(llmClient, toolRegistry, props, toolSchemaValidator, ragProperties(), null);

        AgentContext ctx = new AgentContext(
                List.of(ChatMessage.user("计算 23*47+5")),
                List.of(),
                1
        );

        String result = loop.run(ctx, reporter);

        assertEquals("基于已有信息，答案是 1086。", result);
        assertEquals(
                List.of("THINKING", "ACTING", "OBSERVING", "FINAL"),
                ctx.getStateTrajectory()
        );
        verify(reporter).onFinal("达到最大轮次，强制输出");
    }

    @Test
    void invalidToolArgumentsBecomeObservationInsteadOfExecutingTool() {
        AtomicBoolean executed = new AtomicBoolean(false);
        Tool mockTool = new Tool() {
            @Override
            public String name() { return "search"; }
            @Override
            public String description() { return "搜索"; }
            @Override
            public String parametersSchema() {
                return "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"}},\"required\":[\"query\"]}";
            }
            @Override
            public String execute(Map<String, Object> args) {
                executed.set(true);
                return "should not execute";
            }
        };

        when(toolRegistry.find("search")).thenReturn(Optional.of(mockTool));
        when(llmClient.chatWithTools(anyList(), anyList()))
                .thenReturn(new LlmResponse("", List.of(
                        new ToolCall("call_2", "search", "{}")
                )))
                .thenReturn(new LlmResponse("已根据错误提示收敛。", List.of()));

        AgentLoop loop = new AgentLoop(llmClient, toolRegistry, properties(), toolSchemaValidator, ragProperties(), null);

        AgentContext ctx = new AgentContext(
                List.of(ChatMessage.user("查一下 M3 状态机方案")),
                List.of(),
                5
        );

        String result = loop.run(ctx, reporter);

        assertEquals("已根据错误提示收敛。", result);
        assertEquals(false, executed.get());
        assertEquals("错误：缺失参数: query", ctx.getMessages().get(2).content());
        verify(reporter).onObserving("错误：缺失参数: query");
    }

    @Test
    void eventSequenceFollowsThinkingActingObservingFinal() {
        Tool mockTool = new Tool() {
            @Override
            public String name() { return "search"; }
            @Override
            public String description() { return "搜索知识库"; }
            @Override
            public String parametersSchema() {
                return "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"}},\"required\":[\"query\"]}";
            }
            @Override
            public String execute(Map<String, Object> args) {
                return "检索到 2 条相关片段。";
            }
        };

        when(toolRegistry.find("search")).thenReturn(Optional.of(mockTool));
        when(llmClient.chatWithTools(anyList(), anyList()))
                .thenReturn(new LlmResponse("", List.of(
                        new ToolCall("call_1", "search", "{\"query\":\"M3\"}")
                )))
                .thenReturn(new LlmResponse("M3 包含状态机设计。", List.of()));

        AgentLoop loop = new AgentLoop(llmClient, toolRegistry, properties(), toolSchemaValidator, ragProperties(), null);
        AgentContext ctx = new AgentContext(
                List.of(ChatMessage.user("介绍一下 M3")),
                List.of(),
                5
        );

        String result = loop.run(ctx, reporter);

        assertEquals("M3 包含状态机设计。", result);
        InOrder inOrder = inOrder(reporter);
        inOrder.verify(reporter).onThinking("search");
        inOrder.verify(reporter).onActing("search", "{\"query\":\"M3\"}");
        inOrder.verify(reporter).onObserving("检索到 2 条相关片段。");
        inOrder.verify(reporter).onFinal("生成最终回答");
        verifyNoMoreInteractions(reporter);
    }

    @Test
    void forceFinalReturnsFallbackMessageWhenFinalLlmFails() {
        Tool mockTool = new Tool() {
            @Override
            public String name() { return "search"; }
            @Override
            public String description() { return "搜索"; }
            @Override
            public String parametersSchema() { return "{}"; }
            @Override
            public String execute(Map<String, Object> args) { return "result"; }
        };

        when(toolRegistry.find("search")).thenReturn(Optional.of(mockTool));
        when(llmClient.chatWithTools(anyList(), anyList()))
                .thenReturn(new LlmResponse("", List.of(
                        new ToolCall("call_1", "search", "{}")
                )));
        when(llmClient.chat(anyList())).thenThrow(new RuntimeException("final llm unavailable"));

        LlmProperties props = properties();
        props.setMaxAgentRounds(1);
        AgentLoop loop = new AgentLoop(llmClient, toolRegistry, props, toolSchemaValidator, ragProperties(), null);
        AgentContext ctx = new AgentContext(
                List.of(ChatMessage.user("计算 23*47+5")),
                List.of(),
                1
        );

        String result = loop.run(ctx, reporter);

        assertEquals("抱歉，处理超时，请简化您的问题后重试。", result);
        verify(reporter).onFinal("达到最大轮次，强制输出");
    }

    // ==================== 终答泄漏检测测试 ====================

    private static final String LEAK = "<" + "tool_call";

    @Test
    void cleanFinalAnswerNoLeakDetection() {
        when(llmClient.chatWithTools(anyList(), anyList()))
                .thenReturn(new LlmResponse("这是正常回答，没有泄漏。", List.of()));

        AgentLoop loop = new AgentLoop(llmClient, toolRegistry, properties(), toolSchemaValidator, ragProperties(), null);

        AgentContext ctx = new AgentContext(
                List.of(ChatMessage.user("什么是 RAG")),
                List.of(),
                5,
                "什么是 RAG"
        );

        String result = loop.run(ctx, reporter);

        assertEquals("这是正常回答，没有泄漏。", result);
    }

    @Test
    void leakedFinalAnswerTriggersRagSynthesis() {
        String leakedContent = LEAK + " name=\"search\">" + "leaked" + "</" + "tool_call>";
        when(llmClient.chatWithTools(anyList(), anyList()))
                .thenReturn(new LlmResponse(leakedContent, List.of()));
        when(llmClient.chat(anyList())).thenReturn("这是基于知识库的干净回答。");

        AgentLoop loop = new AgentLoop(llmClient, toolRegistry, properties(), toolSchemaValidator, ragProperties(), null);

        AgentContext ctx = new AgentContext(
                List.of(ChatMessage.user("什么是 RAG")),
                List.of(),
                5,
                "什么是 RAG"
        );
        ctx.addSources(List.of(new RetrievedChunk(1L, 100L, 1, "RAG 是检索增强生成", "doc.pdf", 0.9, 1)));

        String result = loop.run(ctx, reporter);

        assertEquals("这是基于知识库的干净回答。", result);
        verify(reporter).onFinal("生成最终回答");
    }

    @Test
    void leakedFinalAnswerWithNoSourcesReturnsOriginal() {
        String leakedContent = LEAK + " name=\"search\">leaked</" + "tool_call>";
        when(llmClient.chatWithTools(anyList(), anyList()))
                .thenReturn(new LlmResponse(leakedContent, List.of()));

        AgentLoop loop = new AgentLoop(llmClient, toolRegistry, properties(), toolSchemaValidator, ragProperties(), null);

        AgentContext ctx = new AgentContext(
                List.of(ChatMessage.user("什么是 RAG")),
                List.of(),
                5,
                "什么是 RAG"
        );

        String result = loop.run(ctx, reporter);

        assertEquals(leakedContent, result);
    }

    // ==================== 终答引用裁剪测试 ====================

    private static List<RetrievedChunk> chunks(int n) {
        List<RetrievedChunk> list = new java.util.ArrayList<>();
        for (int i = 1; i <= n; i++) {
            list.add(new RetrievedChunk((long) i, (long) (100 + i), i, "内容" + i, "doc" + i + ".pdf", 0.9, i));
        }
        return list;
    }

    @Test
    void citationsPrunedToTopNWhenAccumulatedExceeds() {
        when(llmClient.chatWithTools(anyList(), anyList()))
                .thenReturn(new LlmResponse("正常终答。", List.of()));

        // rerank 未启用（ragProperties() 默认 enabled=false，rerankClient=null）→ fail-open 保留前 8 个
        AgentLoop loop = new AgentLoop(llmClient, toolRegistry, properties(), toolSchemaValidator, ragProperties(), null);

        AgentContext ctx = new AgentContext(
                List.of(ChatMessage.user("查一下")),
                List.of(),
                5,
                "查一下"
        );
        ctx.addSources(chunks(12));

        String result = loop.run(ctx, reporter);

        assertEquals("正常终答。", result);
        assertEquals(8, ctx.getSources().size());
        // fail-open 保序：保留累积顺序前 8 个
        assertEquals(1L, ctx.getSources().get(0).chunkId());
        assertEquals(8L, ctx.getSources().get(7).chunkId());
    }

    @Test
    void citationsNotPrunedWhenBelowTopN() {
        when(llmClient.chatWithTools(anyList(), anyList()))
                .thenReturn(new LlmResponse("正常终答。", List.of()));

        AgentLoop loop = new AgentLoop(llmClient, toolRegistry, properties(), toolSchemaValidator, ragProperties(), null);

        AgentContext ctx = new AgentContext(
                List.of(ChatMessage.user("查一下")),
                List.of(),
                5,
                "查一下"
        );
        ctx.addSources(chunks(3));

        loop.run(ctx, reporter);

        assertEquals(3, ctx.getSources().size());
    }

    @Test
    void leakedAnswerCitationsMatchPrunedSet() {
        String leakedContent = LEAK + " name=\"search\">leaked</" + "tool_call>";
        when(llmClient.chatWithTools(anyList(), anyList()))
                .thenReturn(new LlmResponse(leakedContent, List.of()));
        when(llmClient.chat(anyList())).thenReturn("干净合成答案。");

        AgentLoop loop = new AgentLoop(llmClient, toolRegistry, properties(), toolSchemaValidator, ragProperties(), null);

        AgentContext ctx = new AgentContext(
                List.of(ChatMessage.user("什么是 RAG")),
                List.of(),
                5,
                "什么是 RAG"
        );
        ctx.addSources(chunks(10));

        String result = loop.run(ctx, reporter);

        assertEquals("干净合成答案。", result);
        // 泄漏降级路径：引用集=合成实际使用的剪枝集（top 8）
        assertEquals(8, ctx.getSources().size());
    }

    @Test
    void citationsPrunedByRerankOrderWhenEnabled() {
        com.agenticrag.rag.retrieve.RerankClient rerankClient =
                org.mockito.Mockito.mock(com.agenticrag.rag.retrieve.RerankClient.class);
        // rerank 返回逆序 top 8：index 11,10,9,...,4
        List<com.agenticrag.rag.retrieve.RerankResult> order = new java.util.ArrayList<>();
        for (int i = 11; i >= 4; i--) {
            order.add(new com.agenticrag.rag.retrieve.RerankResult(i, 0.9));
        }
        when(rerankClient.rerank(any(), anyList(), any(Integer.class), any()))
                .thenReturn(Optional.of(order));

        RagProperties props = ragProperties();
        props.getRetrieval().getRerank().setEnabled(true);

        when(llmClient.chatWithTools(anyList(), anyList()))
                .thenReturn(new LlmResponse("正常终答。", List.of()));

        AgentLoop loop = new AgentLoop(llmClient, toolRegistry, properties(), toolSchemaValidator, props, rerankClient);

        AgentContext ctx = new AgentContext(
                List.of(ChatMessage.user("查一下")),
                List.of(),
                5,
                "查一下"
        );
        ctx.addSources(chunks(12));

        loop.run(ctx, reporter);

        assertEquals(8, ctx.getSources().size());
        // 精排顺序：chunkId 12,11,10,...,5（index 11 → chunkId 12）
        assertEquals(12L, ctx.getSources().get(0).chunkId());
        assertEquals(5L, ctx.getSources().get(7).chunkId());
    }
}

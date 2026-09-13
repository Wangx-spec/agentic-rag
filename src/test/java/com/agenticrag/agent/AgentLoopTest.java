package com.agenticrag.agent;

import com.agenticrag.config.LlmProperties;
import com.agenticrag.llm.LlmClient;
import com.agenticrag.llm.dto.ChatMessage;
import com.agenticrag.llm.dto.LlmResponse;
import com.agenticrag.llm.dto.ToolCall;
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

    @Test
    void noToolNeededSingleRoundFinal() {
        AgentLoop loop = new AgentLoop(llmClient, toolRegistry, properties(), toolSchemaValidator);

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

        AgentLoop loop = new AgentLoop(llmClient, toolRegistry, properties(), toolSchemaValidator);

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

        AgentLoop loop = new AgentLoop(llmClient, toolRegistry, props, toolSchemaValidator);

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

        AgentLoop loop = new AgentLoop(llmClient, toolRegistry, properties(), toolSchemaValidator);

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

        AgentLoop loop = new AgentLoop(llmClient, toolRegistry, properties(), toolSchemaValidator);
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
        AgentLoop loop = new AgentLoop(llmClient, toolRegistry, props, toolSchemaValidator);
        AgentContext ctx = new AgentContext(
                List.of(ChatMessage.user("计算 23*47+5")),
                List.of(),
                1
        );

        String result = loop.run(ctx, reporter);

        assertEquals("抱歉，处理超时，请简化您的问题后重试。", result);
        verify(reporter).onFinal("达到最大轮次，强制输出");
    }
}
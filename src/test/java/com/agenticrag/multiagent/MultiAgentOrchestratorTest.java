package com.agenticrag.multiagent;

import com.agenticrag.llm.dto.ChatMessage;
import com.agenticrag.memory.ConversationMemory;
import com.agenticrag.multiagent.dto.SubTaskResult;
import com.agenticrag.rag.retrieve.RetrievedChunk;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MultiAgentOrchestratorTest {

    private final LeaderAgent leaderAgent = mock(LeaderAgent.class);
    private final SubAgentExecutor subAgentExecutor = mock(SubAgentExecutor.class);
    private final Aggregator aggregator = mock(Aggregator.class);
    private final ConversationMemory memory = mock(ConversationMemory.class);
    private final Executor executor = Runnable::run;

    private final MultiAgentOrchestrator orchestrator = new MultiAgentOrchestrator(
            leaderAgent, subAgentExecutor, aggregator, memory, executor
    );

    @Test
    void orchestrateRunsAggregatorWhenAtLeastOneSubTaskSucceeds() {
        CapturingSseEmitter emitter = new CapturingSseEmitter();
        RetrievedChunk chunk = new RetrievedChunk(1L, 10L, 1, "片段", "guide.md", 1.0, 1);
        when(leaderAgent.plan("总问题")).thenReturn(List.of("q1", "q2"));
        when(subAgentExecutor.execute("q1", 1)).thenReturn(SubTaskResult.ok(1, "q1", "结论1", List.of(chunk)));
        when(subAgentExecutor.execute("q2", 2)).thenReturn(SubTaskResult.failure(2, "q2", "检索无结果"));
        when(aggregator.aggregate(eq("总问题"), any(), eq(emitter)))
                .thenReturn(new Aggregator.AggregateResult("最终回答", List.of(chunk)));

        boolean handled = orchestrator.orchestrate("总问题", emitter, "s1");

        assertTrue(handled);
        assertTrue(emitter.completed);
        assertTrue(emitter.payloads.stream().anyMatch(text -> text.contains("拆解完成")));
        assertTrue(emitter.payloads.stream().anyMatch(text -> text.contains("子任务 1 开始")));
        assertTrue(emitter.payloads.stream().anyMatch(text -> text.contains("子任务 1 完成")));
        assertTrue(emitter.payloads.stream().anyMatch(text -> text.contains("开始汇总回答")));
        ArgumentCaptor<List<SubTaskResult>> captor = ArgumentCaptor.forClass(List.class);
        verify(aggregator).aggregate(eq("总问题"), captor.capture(), eq(emitter));
        assertTrue(captor.getValue().size() == 1 && captor.getValue().get(0).success());
        verify(memory).append("s1", ChatMessage.assistant("最终回答"));
    }

    @Test
    void orchestrateReturnsFalseWhenLeaderProducesSingleQuestion() {
        CapturingSseEmitter emitter = new CapturingSseEmitter();
        when(leaderAgent.plan("单问题")).thenReturn(List.of("单问题"));

        boolean handled = orchestrator.orchestrate("单问题", emitter, "s1");

        assertFalse(handled);
        verify(subAgentExecutor, never()).execute(any(), any(Integer.class));
        verify(aggregator, never()).aggregate(any(), any(), any());
        verify(memory, never()).append(any(), any());
    }

    @Test
    void orchestrateReturnsFalseWhenAllSubTasksFail() {
        CapturingSseEmitter emitter = new CapturingSseEmitter();
        when(leaderAgent.plan("总问题")).thenReturn(List.of("q1", "q2"));
        when(subAgentExecutor.execute("q1", 1)).thenReturn(SubTaskResult.failure(1, "q1", "失败1"));
        when(subAgentExecutor.execute("q2", 2)).thenReturn(SubTaskResult.failure(2, "q2", "失败2"));

        boolean handled = orchestrator.orchestrate("总问题", emitter, "s1");

        assertFalse(handled);
        verify(aggregator, never()).aggregate(any(), any(), any());
        verify(memory, never()).append(any(), any());
    }

    static class CapturingSseEmitter extends SseEmitter {
        final List<String> payloads = new ArrayList<>();
        boolean completed;

        @Override
        public synchronized void send(SseEventBuilder builder) throws IOException {
            Set<ResponseBodyEmitter.DataWithMediaType> built = builder.build();
            for (ResponseBodyEmitter.DataWithMediaType item : built) {
                payloads.add(String.valueOf(item.getData()));
            }
        }

        @Override
        public synchronized void complete() {
            completed = true;
        }
    }
}

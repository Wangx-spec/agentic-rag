package com.agenticrag.multiagent;

import com.agenticrag.llm.LlmClient;
import com.agenticrag.multiagent.dto.SubTaskResult;
import com.agenticrag.rag.retrieve.RetrievedChunk;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AggregatorTest {

    private final LlmClient llmClient = mock(LlmClient.class);
    private final Aggregator aggregator = new Aggregator(llmClient);

    @Test
    void aggregateMergesAndDeduplicatesSources() {
        RetrievedChunk shared = new RetrievedChunk(1L, 10L, 1, "共享片段", "guide.md", 0.9, 1);
        RetrievedChunk another = new RetrievedChunk(2L, 10L, 2, "另一个片段", "guide.md", 0.8, 2);
        List<SubTaskResult> results = List.of(
                SubTaskResult.ok(1, "q1", "结论1 [1]", List.of(shared, another)),
                SubTaskResult.ok(2, "q2", "结论2 [1]", List.of(shared))
        );
        CapturingSseEmitter emitter = new CapturingSseEmitter();
        when(llmClient.chatStream(anyList(), any())).thenAnswer(invocation -> {
            LlmClient.StreamListener listener = invocation.getArgument(1);
            listener.onAnswer("综合回答 [1][2]");
            return "综合回答 [1][2]";
        });

        Aggregator.AggregateResult result = aggregator.aggregate("总问题", results, emitter);

        assertEquals("综合回答 [1][2]", result.answer());
        assertEquals(2, result.sources().size());
        assertEquals(1, result.sources().get(0).rank());
        assertEquals(2, result.sources().get(1).rank());
        assertTrue(emitter.payloads.stream().anyMatch(payload -> payload.contains("综合回答")));
    }

    static class CapturingSseEmitter extends SseEmitter {
        final List<String> payloads = new ArrayList<>();

        @Override
        public synchronized void send(SseEventBuilder builder) throws IOException {
            Set<ResponseBodyEmitter.DataWithMediaType> built = builder.build();
            for (ResponseBodyEmitter.DataWithMediaType item : built) {
                payloads.add(String.valueOf(item.getData()));
            }
        }
    }
}

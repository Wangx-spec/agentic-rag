package com.agenticrag.service;

import com.agenticrag.rag.retrieve.RetrievedChunk;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@Slf4j
@RequiredArgsConstructor
public class SseChatEventSink implements ChatEventSink {

    private final SseEmitter emitter;

    @Override
    public void onThinking(String text) {
        send("thinking", Map.of("text", text));
    }

    @Override
    public void onDelta(String text) {
        send("delta", Map.of("text", text));
    }

    @Override
    public void onDone(List<RetrievedChunk> sources) {
        send("done", Map.of("sources", buildSourcesPayload(sources)));
    }

    @Override
    public void onError(String message) {
        send("error", Map.of("message", message));
    }

    private List<Map<String, Object>> buildSourcesPayload(List<RetrievedChunk> retrieved) {
        if (retrieved == null || retrieved.isEmpty()) {
            return List.of();
        }
        return retrieved.stream()
                .map(chunk -> Map.<String, Object>of(
                        "n", chunk.rank(),
                        "docName", chunk.docName(),
                        "snippet", chunk.content()
                ))
                .toList();
    }

    private void send(String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data));
        } catch (IOException e) {
            log.debug("Failed to send SSE event {}", event, e);
        }
    }
}

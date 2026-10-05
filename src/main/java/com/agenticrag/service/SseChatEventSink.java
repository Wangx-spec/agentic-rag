package com.agenticrag.service;

import com.agenticrag.agent.EvidenceRegistry;
import com.agenticrag.rag.retrieve.RetrievedChunk;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
public class SseChatEventSink implements ChatEventSink {

    private final SseEmitter emitter;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public SseChatEventSink(SseEmitter emitter) {
        this.emitter = emitter;
        this.emitter.onCompletion(() -> closed.set(true));
        this.emitter.onTimeout(() -> closed.set(true));
        this.emitter.onError(error -> closed.set(true));
    }

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

    @Override
    public void onTable(Map<String, Object> payload) {
        send("table", payload);
    }

    @Override
    public void onEvidence(EvidenceRegistry.Evidence evidence) {
        if (evidence == null) {
            return;
        }
        send("evidence", Map.of(
                "id", evidence.id(),
                "type", evidence.type().name(),
                "summary", evidence.summary(),
                "payloadRef", evidence.payloadRef()
        ));
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
        if (closed.get()) {
            return;
        }
        try {
            emitter.send(SseEmitter.event().name(event).data(data));
        } catch (IllegalStateException e) {
            closed.set(true);
            log.debug("SSE event {} skipped because emitter is already completed", event, e);
        } catch (IOException e) {
            closed.set(true);
            log.debug("SSE event {} failed because client connection was closed", event, e);
        }
    }
}

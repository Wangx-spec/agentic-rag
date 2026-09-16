package com.agenticrag.api;

import com.agenticrag.service.ChatMode;
import com.agenticrag.service.ChatService;
import com.agenticrag.service.SseChatEventSink;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api")
@Slf4j
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;

    @PostMapping(path = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@RequestBody ChatRequest req, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("X-Accel-Buffering", "no");

        SseEmitter emitter = new SseEmitter(0L);
        SseChatEventSink sink = new SseChatEventSink(emitter);

        if (req == null || req.message() == null || req.message().isBlank()) {
            sink.onError("message 不能为空");
            emitter.complete();
            return emitter;
        }

        String sessionId = (req.sessionId() == null || req.sessionId().isBlank()) ? "default" : req.sessionId();
        ChatMode mode = resolveMode(req);
        CompletableFuture.runAsync(() -> {
            try {
                chatService.chat(sessionId, req.message(), mode, sink);
            } catch (IllegalArgumentException | IllegalStateException e) {
                sink.onError(e.getMessage());
            } catch (Exception e) {
                log.warn("Chat request failed unexpectedly, sessionId={}, mode={}", sessionId, mode, e);
                sink.onError("聊天链路发生未预期异常");
            } finally {
                emitter.complete();
            }
        });

        return emitter;
    }

    @DeleteMapping("/memory/{sessionId}")
    public ResponseEntity<Void> clearMemory(@PathVariable String sessionId) {
        chatService.clearMemory(sessionId);
        return ResponseEntity.noContent().build();
    }

    private ChatMode resolveMode(ChatRequest req) {
        if (req.mode() != null && !req.mode().isBlank()) {
            return ChatMode.from(req.mode());
        }
        if (req.agent() != null) {
            return req.agent() ? ChatMode.AGENT : ChatMode.RAG;
        }
        if (req.kb() != null) {
            return req.kb() ? ChatMode.RAG : ChatMode.PLAIN;
        }
        return ChatMode.AGENT;
    }

    public record ChatRequest(String sessionId, String message, String mode, Boolean agent, Boolean kb) {
    }
}

package com.agenticrag.api;

import com.agenticrag.config.LlmProperties;
import com.agenticrag.llm.LlmClient;
import com.agenticrag.llm.dto.ChatMessage;
import com.agenticrag.memory.ConversationMemory;
import com.agenticrag.rag.retrieve.HybridRetriever;
import com.agenticrag.rag.retrieve.RetrievedChunk;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api")
@Slf4j
@RequiredArgsConstructor
public class ChatController {

    private final LlmProperties llmProperties;
    private final LlmClient llmClient;
    private final ConversationMemory memory;
    private final HybridRetriever hybridRetriever;

    @PostMapping(path = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@RequestBody ChatRequest req, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("X-Accel-Buffering", "no");

        SseEmitter emitter = new SseEmitter(0L);

        if (req == null || req.message() == null || req.message().isBlank()) {
            sendErrorAndComplete(emitter, "message 不能为空");
            return emitter;
        }
        if (!llmProperties.isConfigured()) {
            sendErrorAndComplete(emitter, "LLM 未配置，请设置 LLM_API_KEY 环境变量");
            return emitter;
        }

        String sessionId = (req.sessionId() == null || req.sessionId().isBlank()) ? "default" : req.sessionId();
        memory.append(sessionId, ChatMessage.user(req.message()));

        List<RetrievedChunk> retrieved = hybridRetriever.retrieve(req.message());
        List<ChatMessage> messages = buildMessages(sessionId, retrieved);

        CompletableFuture.runAsync(() -> {
            try {
                String full = llmClient.chatStream(messages, delta -> {
                            send(emitter, "delta", Map.of("text", delta));
                });
                memory.append(sessionId, ChatMessage.assistant(full));
                send(emitter, "done", Map.of(
                        "sources", retrieved.stream()
                                .map(chunk -> Map.of(
                                        "n", chunk.rank(),
                                        "docName", chunk.docName(),
                                        "snippet", chunk.content()
                                ))
                                .toList()
                ));
                emitter.complete();
            } catch (Exception e) {
                log.warn("Chat SSE failed, sessionId={}", sessionId, e);
                sendErrorAndComplete(emitter, e.getMessage());
            }
        });

        return emitter;
    }

    private List<ChatMessage> buildMessages(String sessionId, List<RetrievedChunk> retrieved) {
        List<ChatMessage> messages = new ArrayList<>();
        if (retrieved == null || retrieved.isEmpty()) {
            messages.add(new ChatMessage("system", "你是一个乐于助人的中文助手，回答简洁清晰。"));
        } else {
            StringBuilder context = new StringBuilder();
            context.append("你是一个基于上下文回答问题的中文助手。请优先利用给定上下文回答，并在句末用 [n] 标注引用；如果上下文不足，请明确说明。\n\n");
            for (RetrievedChunk chunk : retrieved) {
                context.append("[").append(chunk.rank()).append("] ")
                        .append(chunk.docName()).append("：")
                        .append(chunk.content()).append("\n\n");
            }
            messages.add(ChatMessage.system(context.toString()));
        }
        messages.addAll(memory.load(sessionId, llmProperties.getMemoryRounds() * 2));
        return messages;
    }

    private void send(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data));
        } catch (IOException e) {
            log.debug("Failed to send SSE event {}", event, e);
            emitter.complete();
        }
    }

    private void sendErrorAndComplete(SseEmitter emitter, String message) {
        send(emitter, "error", Map.of("message", message));
        emitter.complete();
    }

    public record ChatRequest(String sessionId, String message) {
    }
}

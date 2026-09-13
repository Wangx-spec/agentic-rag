package com.agenticrag.api;

import com.agenticrag.agent.AgentContext;
import com.agenticrag.agent.AgentLoop;
import com.agenticrag.agent.SseStepReporter;
import com.agenticrag.agent.StepReporter;
import com.agenticrag.config.LlmProperties;
import com.agenticrag.llm.LlmClient;
import com.agenticrag.intent.IntentClassifier;
import com.agenticrag.llm.dto.ChatMessage;
import com.agenticrag.llm.dto.ToolSchema;
import com.agenticrag.memory.ConversationMemory;
import com.agenticrag.multiagent.MultiAgentOrchestrator;
import com.agenticrag.rag.retrieve.HybridRetriever;
import com.agenticrag.rag.retrieve.RetrievedChunk;
import com.agenticrag.tool.ToolRegistry;
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

    private static final double IMPLICIT_ROUTE_MIN_RRF_SCORE = 0.02;

    private final LlmProperties llmProperties;
    private final LlmClient llmClient;
    private final ConversationMemory memory;
    private final HybridRetriever hybridRetriever;
    private final AgentLoop agentLoop;
    private final ToolRegistry toolRegistry;
    private final IntentClassifier intentClassifier;
    private final MultiAgentOrchestrator multiAgentOrchestrator;

    /**
     * 统一聊天入口，接收用户消息并返回 SSE 流
     */
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

        ChatMode mode = resolveMode(req);
        CompletableFuture.runAsync(() -> handleChat(emitter, sessionId, req.message(), mode));

        return emitter;
    }

    /**
     * 清除指定会话的记忆
     */
    @DeleteMapping("/memory/{sessionId}")
    public ResponseEntity<Void> clearMemory(@PathVariable String sessionId) {
        if (sessionId != null && !sessionId.isBlank()) {
            memory.clear(sessionId);
        }
        return ResponseEntity.noContent().build();
    }

    /**
     * 构建对话消息列表（带或不带检索结果的上下文）
     */
    private List<ChatMessage> buildMessages(String sessionId, List<RetrievedChunk> retrieved) {
        List<ChatMessage> messages = new ArrayList<>();
        if (retrieved == null || retrieved.isEmpty()) {
            messages.add(new ChatMessage("system", "你是一个乐于助人的中文助手，回答简洁清晰。"));
        } else {
            StringBuilder context = new StringBuilder();
            context.append("你是一个基于给定上下文回答问题的中文助手。请优先利用给定上下文回答，并在句末用 [n] 标注引用；如果上下文不足，请明确说明。\n\n");
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

    /**
     * 发送 SSE 事件到客户端
     */
    private void send(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data));
        } catch (IOException e) {
            log.debug("Failed to send SSE event {}", event, e);
            // Don't call emitter.complete() here - let it be handled by container's error callback
            // to avoid "non-container thread attempted to use AsyncContext after error" issue
        }
    }

    /**
     * 发送错误消息并结束 SSE 流
     */
    private void sendErrorAndComplete(SseEmitter emitter, String message) {
        send(emitter, "error", Map.of("message", message));
        emitter.complete();
    }

    /**
     * 运行 Agent 循环：调用工具后生成最终回答，并下发收集到的引用来源
     */
    private void runAgent(SseEmitter emitter, String sessionId) {
        AgentContext ctx = new AgentContext(buildAgentMessages(sessionId), toToolSchemas(), llmProperties.getMaxAgentRounds());
        StepReporter reporter = new SseStepReporter((event, data) -> send(emitter, event, data));
        String finalAnswer = agentLoop.run(ctx, reporter);
        streamAnswer(emitter, finalAnswer);
        memory.append(sessionId, ChatMessage.assistant(finalAnswer));
        send(emitter, "done", Map.of("sources", buildSourcesPayload(ctx.getSources())));
        emitter.complete();
    }

    /**
     * 转换工具注册表中的工具为 ToolSchema 格式，供 LLM 调用
     */
    private List<ToolSchema> toToolSchemas() {
        return toolRegistry.all().values().stream()
                .map(tool -> new ToolSchema(tool.name(), tool.description(), tool.parametersSchema()))
                .toList();
    }

    /**
     * 构建 Agent 模式的输入消息（包含工具使用指导的系统提示）
     */
    private List<ChatMessage> buildAgentMessages(String sessionId) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system("你是一个乐于助人的中文助手。需要查询知识库或计算时，请先调用对应工具再作答；最终回答简洁清晰，并在句末用 [n] 标注引用来源。"));
        messages.addAll(memory.load(sessionId, llmProperties.getMemoryRounds() * 2));
        return messages;
    }

    /**
     * 构建 sources 载荷数据，用于前端渲染引用来源面板
     */
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

    /**
     * 异常兜底：发送保守的降级答案并结束会话
     */
    private void sendFallbackAnswerAndComplete(SseEmitter emitter, String sessionId, String userMessage) {
        send(emitter, "thinking", Map.of("text", "⚠️ 普通检索链路也发生异常，正在返回保守兜底答案"));
        String fallback = buildFallbackAnswer(userMessage);
        streamAnswer(emitter, fallback);
        memory.append(sessionId, ChatMessage.assistant(fallback));
        send(emitter, "done", Map.of("sources", List.of()));
        emitter.complete();
    }

    /**
     * 根据用户问题类型构建兜底答案（避免误导）
     */
    private String buildFallbackAnswer(String userMessage) {
        if (looksLikeMathQuestion(userMessage)) {
            return "抱歉，计算链路暂时不可用，我现在没法可靠给出这个结果。你可以稍后重试，或把表达式拆成更短的步骤再问我。";
        }
        if (userMessage != null && (userMessage.contains("文档") || userMessage.contains("文件") || userMessage.contains("说明") || userMessage.contains("资料"))) {
            return "抱歉，我刚刚在检索文档时遇到临时异常，暂时没法可靠定位到具体段落。你可以稍后重试，或直接告诉我文档名和关键词，我会优先帮你缩小范围。";
        }
        return "抱歉，当前检索与生成链路都出现了临时异常。为避免误导，我先不输出不确定结论。你可以稍后重试，或把问题缩短后再发一次。";
    }

    /**
     * 判断用户问题是否像数学表达式
     */
    private boolean looksLikeMathQuestion(String userMessage) {
        return userMessage != null && userMessage.matches(".*[0-9][0-9+\\-*/(). ]*.*");
    }

    /**
     * 将文本按固定步长分片，逐片以 SSE delta 事件推送到客户端，模拟打字流式输出效果
     */
    private void streamAnswer(SseEmitter emitter, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        int step = 4;
        for (int i = 0; i < text.length(); i += step) {
            String chunk = text.substring(i, Math.min(i + step, text.length()));
            send(emitter, "delta", Map.of("text", chunk));
        }
    }

    /**
     * 聊天请求的核心分发入口：根据解析出的 ChatMode 调用对应链路，异常时降级到兜底答案
     */
    private void handleChat(SseEmitter emitter, String sessionId, String userMessage, ChatMode mode) {
        try {
            switch (mode) {
                case AGENT -> runAgentSafely(emitter, sessionId, userMessage);
                case RAG -> runRag(emitter, sessionId, userMessage);
                case PLAIN -> runPlain(emitter, sessionId);
                case AUTO -> runAuto(emitter, sessionId, userMessage);
                case MULTI_AGENT -> runMultiAgentSafely(emitter, sessionId, userMessage);
            }
        } catch (Exception e){
            log.warn("Chat failed, sessionId={}, mode={}", sessionId, mode, e);
            sendFallbackAnswerAndComplete(emitter, sessionId, userMessage);        
        } 
    }
    /**
     * 自动路由：根据意图识别结果选择对应的处理链路
     * - CHAT/OFF_TOPIC → Plain 模式（普通对话）
     * - KB_QA → RAG 模式（知识库问答）
     * - TOOL_TASK → Agent 模式（工具调用）
     * - UNKNOWN → 隐式路由兜底
     */
    private void runAuto(SseEmitter emitter, String sessionId, String userMessage) {
        IntentClassifier.IntentResult result = intentClassifier.classify(userMessage);
        switch (result.intent()) {
            case CHAT, OFF_TOPIC -> runPlain(emitter, sessionId);
            case KB_QA -> runRag(emitter, sessionId, userMessage);
            case MULTI_TASK -> runMultiAgentSafely(emitter, sessionId, userMessage);
            case TOOL_TASK -> runAgentSafely(emitter, sessionId, userMessage);
            case UNKNOWN -> runImplicitRoute(emitter, sessionId, userMessage);
        }
    }

    /**
     * 隐式路由兜底逻辑：意图识别彻底失败时的处理策略
     * 1. 检索知识库并按 RRF 分数过滤（>= 0.02 才认为相关）
     * 2. 有高分结果 → RAG 模式（可能是知识库问答）
     * 3. 无高分结果 → Plain 模式（可能是闲聊或常识问答）
     */
    private void runImplicitRoute(SseEmitter emitter, String sessionId, String userMessage) {
        List<RetrievedChunk> retrieved = hybridRetriever.retrieve(userMessage);
        List<RetrievedChunk> relevant = retrieved == null ? List.of() : retrieved.stream()
                .filter(c -> c.rrfScore() >= IMPLICIT_ROUTE_MIN_RRF_SCORE)
                .toList();
        
        if (relevant.isEmpty()) {
            runPlain(emitter, sessionId);
            return;
        }
        runRagWithRetrieved(emitter, sessionId, relevant);
    }

    /**
     * Agent 模式安全执行：Agent 失败时降级到 RAG 模式
     */
    private void runAgentSafely(SseEmitter emitter, String sessionId, String userMessage) {
        try {
            runAgent(emitter, sessionId);
        } catch (Exception e) {
            log.warn("Agent chat failed, sessionId={}, fallback to RAG", sessionId, e);
            send(emitter, "thinking", Map.of("text", "⚠️ Agent 链路异常，正在降级到普通检索回答"));
            runRag(emitter, sessionId, userMessage);
        }
    }

    /**
     * 多 Agent 模式：由编排器完成拆解、并行子任务和汇总；若编排器返回 false，则降级到普通 RAG。
     */
    private void runMultiAgent(SseEmitter emitter, String sessionId, String userMessage) {
        boolean handled = multiAgentOrchestrator.orchestrate(userMessage, emitter, sessionId);
        if (!handled) {
            send(emitter, "thinking", Map.of("text", "⚠️ 多 Agent 链路不适用，正在降级到普通检索回答"));
            runRag(emitter, sessionId, userMessage);
        }
    }

    /**
     * 多 Agent 模式安全执行：编排链路异常时降级到 RAG。
     */
    private void runMultiAgentSafely(SseEmitter emitter, String sessionId, String userMessage) {
        try {
            runMultiAgent(emitter, sessionId, userMessage);
        } catch (Exception e) {
            log.warn("Multi-agent chat failed, sessionId={}, fallback to RAG", sessionId, e);
            send(emitter, "thinking", Map.of("text", "⚠️ 多 Agent 链路异常，正在降级到普通检索回答"));
            runRag(emitter, sessionId, userMessage);
        }
    }

    /**
     * Plain 模式：不检索知识库，直接将会话历史送入 LLM 生成回答
     */
    private void runPlain(SseEmitter emitter, String sessionId) {
        List<ChatMessage> messages = buildMessages(sessionId, List.of());
        streamLlmAnswer(emitter, sessionId, messages, List.of());
    }

    
    /**
     * RAG 模式：先通过混合检索获取相关文档片段，再结合上下文生成回答
     */
    private void runRag(SseEmitter emitter, String sessionId, String userMessage){
        List<RetrievedChunk> retrieved = hybridRetriever.retrieve(userMessage);
        runRagWithRetrieved(emitter, sessionId, retrieved);
    }

    /**
     * 使用已检索到的文档片段构建上下文并调用 LLM 生成回答，避免重复检索
     */
    private void runRagWithRetrieved(SseEmitter emitter, String sessionId, List<RetrievedChunk> relevant){
        List<ChatMessage> messages = buildMessages(sessionId, relevant);
        streamLlmAnswer(emitter, sessionId, messages, buildSourcesPayload(relevant));
    }

    /**
     * 调用 LLM 流式接口生成回答，将 thinking 和 delta 事件实时推送到客户端，完成后保存记忆并发送 done 事件
     */
    private void streamLlmAnswer(SseEmitter emitter, String sessionId, List<ChatMessage> messages, List<Map<String, Object>> sources) {
        String full = llmClient.chatStream(messages, new LlmClient.StreamListener(){
            @Override
            public void onThinking(String delta) {
                send(emitter, "thinking", Map.of("text", delta));
            }
            @Override
            public void onAnswer(String delta) {
                send(emitter, "delta", Map.of("text", delta));
            }
        });
        memory.append(sessionId, ChatMessage.assistant(full));
        send(emitter, "done", Map.of("sources", sources));
        emitter.complete();
    }

    /**
     * 解析请求中的聊天模式：优先取 mode 字段，其次兼容旧版 agent/kb 布尔字段，默认使用 AGENT 模式
     */
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

    enum ChatMode {
        AUTO, PLAIN, RAG, AGENT, MULTI_AGENT;

        static ChatMode from(String raw) {
            return switch (raw.toLowerCase()) {
                case "auto" -> AUTO;
                case "plain" -> PLAIN;
                case "rag" -> RAG;
                case "agent" -> AGENT;
                case "multi-agent" -> MULTI_AGENT;
                default -> throw new IllegalArgumentException("不支持的 mode: " + raw);
            };
        }
    }


    public record ChatRequest(String sessionId, String message, String mode, Boolean agent, Boolean kb) {

    }
}

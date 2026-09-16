package com.agenticrag.service;

import com.agenticrag.agent.AgentContext;
import com.agenticrag.agent.AgentLoop;
import com.agenticrag.agent.StepReporter;
import com.agenticrag.config.LlmProperties;
import com.agenticrag.intent.Intent;
import com.agenticrag.intent.IntentClassifier;
import com.agenticrag.llm.LlmClient;
import com.agenticrag.llm.dto.ChatMessage;
import com.agenticrag.llm.dto.ToolSchema;
import com.agenticrag.memory.ConversationMemory;
import com.agenticrag.multiagent.MultiAgentOrchestrator;
import com.agenticrag.rag.retrieve.HybridRetriever;
import com.agenticrag.rag.retrieve.RetrievedChunk;
import com.agenticrag.tool.ToolRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private static final double IMPLICIT_ROUTE_MIN_RRF_SCORE = 0.02;

    private final LlmProperties llmProperties;
    private final LlmClient llmClient;
    private final ConversationMemory memory;
    private final HybridRetriever hybridRetriever;
    private final AgentLoop agentLoop;
    private final ToolRegistry toolRegistry;
    private final IntentClassifier intentClassifier;
    private final MultiAgentOrchestrator multiAgentOrchestrator;

    public ChatResult chat(String sessionId, String userMessage, ChatMode mode, ChatEventSink sink) {
        if (userMessage == null || userMessage.isBlank()) {
            throw new IllegalArgumentException("message 不能为空");
        }
        if (!llmProperties.isConfigured()) {
            throw new IllegalStateException("LLM 未配置，请设置 LLM_API_KEY 环境变量");
        }
        String actualSessionId = (sessionId == null || sessionId.isBlank()) ? "default" : sessionId;
        memory.append(actualSessionId, ChatMessage.user(userMessage));
        return handleChat(sink, actualSessionId, userMessage, mode);
    }

    public void clearMemory(String sessionId) {
        if (sessionId != null && !sessionId.isBlank()) {
            memory.clear(sessionId);
        }
    }

    private ChatResult handleChat(ChatEventSink sink, String sessionId, String userMessage, ChatMode mode) {
        try {
            return switch (mode) {
                case AGENT -> runAgentSafely(sink, sessionId, userMessage);
                case RAG -> runRag(sink, sessionId, userMessage);
                case PLAIN -> runPlain(sink, sessionId);
                case AUTO -> runAuto(sink, sessionId, userMessage);
                case MULTI_AGENT -> runMultiAgentSafely(sink, sessionId, userMessage);
            };
        } catch (Exception e) {
            log.warn("Chat failed, sessionId={}, mode={}", sessionId, mode, e);
            return sendFallbackAnswer(sink, sessionId, userMessage, mode);
        }
    }

    private ChatResult runAuto(ChatEventSink sink, String sessionId, String userMessage) {
        IntentClassifier.IntentResult result = intentClassifier.classify(userMessage);
        return switch (result.intent()) {
            case CHAT, OFF_TOPIC -> withRoutedIntent(runPlain(sink, sessionId), result.intent());
            case KB_QA -> withRoutedIntent(runRag(sink, sessionId, userMessage), result.intent());
            case MULTI_TASK -> withRoutedIntent(runMultiAgentSafely(sink, sessionId, userMessage), result.intent());
            case TOOL_TASK -> withRoutedIntent(runAgentSafely(sink, sessionId, userMessage), result.intent());
            case UNKNOWN -> withRoutedIntent(runImplicitRoute(sink, sessionId, userMessage), result.intent());
        };
    }

    private ChatResult withRoutedIntent(ChatResult result, Intent intent) {
        return new ChatResult(
                result.answer(),
                result.sources(),
                result.executedMode(),
                intent,
                result.toolInvocations(),
                result.degraded()
        );
    }

    private ChatResult runImplicitRoute(ChatEventSink sink, String sessionId, String userMessage) {
        List<RetrievedChunk> retrieved = hybridRetriever.retrieve(userMessage);
        List<RetrievedChunk> relevant = retrieved == null ? List.of() : retrieved.stream()
                .filter(c -> c.rrfScore() >= IMPLICIT_ROUTE_MIN_RRF_SCORE)
                .toList();

        if (relevant.isEmpty()) {
            return runPlain(sink, sessionId);
        }
        return runRagWithRetrieved(sink, sessionId, relevant);
    }

    private ChatResult runAgentSafely(ChatEventSink sink, String sessionId, String userMessage) {
        try {
            return runAgent(sink, sessionId);
        } catch (Exception e) {
            log.warn("Agent chat failed, sessionId={}, fallback to RAG", sessionId, e);
            sink.onThinking("⚠️ Agent 链路异常，正在降级到普通检索回答");
            ChatResult ragResult = runRag(sink, sessionId, userMessage);
            return new ChatResult(
                    ragResult.answer(),
                    ragResult.sources(),
                    ragResult.executedMode(),
                    ragResult.routedIntent(),
                    ragResult.toolInvocations(),
                    true
            );
        }
    }

    private ChatResult runMultiAgentSafely(ChatEventSink sink, String sessionId, String userMessage) {
        try {
            return runMultiAgent(sink, sessionId, userMessage);
        } catch (Exception e) {
            log.warn("Multi-agent chat failed, sessionId={}, fallback to RAG", sessionId, e);
            sink.onThinking("⚠️ 多 Agent 链路异常，正在降级到普通检索回答");
            ChatResult ragResult = runRag(sink, sessionId, userMessage);
            return new ChatResult(
                    ragResult.answer(),
                    ragResult.sources(),
                    ragResult.executedMode(),
                    ragResult.routedIntent(),
                    ragResult.toolInvocations(),
                    true
            );
        }
    }

    private ChatResult runMultiAgent(ChatEventSink sink, String sessionId, String userMessage) {
        RecordingSink recordingSink = new RecordingSink(sink);
        boolean handled = multiAgentOrchestrator.orchestrate(userMessage, recordingSink, sessionId);
        if (!handled) {
            sink.onThinking("⚠️ 多 Agent 链路不适用，正在降级到普通检索回答");
            ChatResult ragResult = runRag(sink, sessionId, userMessage);
            return new ChatResult(
                    ragResult.answer(),
                    ragResult.sources(),
                    ragResult.executedMode(),
                    ragResult.routedIntent(),
                    ragResult.toolInvocations(),
                    true
            );
        }
        return new ChatResult(
                recordingSink.answer(),
                recordingSink.sources(),
                ChatMode.MULTI_AGENT,
                null,
                List.of(),
                false
        );
    }

    private ChatResult runPlain(ChatEventSink sink, String sessionId) {
        List<ChatMessage> messages = buildMessages(sessionId, List.of());
        return streamLlmAnswer(sink, sessionId, messages, List.of(), ChatMode.PLAIN, List.of(), false);
    }

    private ChatResult runRag(ChatEventSink sink, String sessionId, String userMessage) {
        List<RetrievedChunk> retrieved = hybridRetriever.retrieve(userMessage);
        return runRagWithRetrieved(sink, sessionId, retrieved);
    }

    private ChatResult runRagWithRetrieved(ChatEventSink sink, String sessionId, List<RetrievedChunk> relevant) {
        List<RetrievedChunk> safeRelevant = relevant == null ? List.of() : relevant;
        List<ChatMessage> messages = buildMessages(sessionId, safeRelevant);
        return streamLlmAnswer(sink, sessionId, messages, safeRelevant, ChatMode.RAG, List.of(), false);
    }

    private ChatResult runAgent(ChatEventSink sink, String sessionId) {
        List<ToolInvocation> toolInvocations = new ArrayList<>();
        AgentContext ctx = new AgentContext(buildAgentMessages(sessionId), toToolSchemas(), llmProperties.getMaxAgentRounds());
        StepReporter reporter = new StepReporter() {
            @Override
            public void onThinking(String toolName) {
                sink.onThinking("⚙️ 思考：判断需要调用工具 " + toolName);
            }

            @Override
            public void onActing(String toolName, String arguments) {
                toolInvocations.add(new ToolInvocation(toolName, arguments));
                sink.onThinking("🔧 调用工具：" + toolName + "(" + arguments + ")");
            }

            @Override
            public void onObserving(String summary) {
                sink.onThinking("👁 观察：" + summary);
            }

            @Override
            public void onFinal(String message) {
                sink.onThinking("✅ " + message);
            }
        };
        String finalAnswer = agentLoop.run(ctx, reporter);
        streamAnswer(sink, finalAnswer);
        memory.append(sessionId, ChatMessage.assistant(finalAnswer));
        sink.onDone(ctx.getSources());
        return new ChatResult(finalAnswer, ctx.getSources(), ChatMode.AGENT, null, List.copyOf(toolInvocations), false);
    }

    private ChatResult streamLlmAnswer(ChatEventSink sink,
                                       String sessionId,
                                       List<ChatMessage> messages,
                                       List<RetrievedChunk> sources,
                                       ChatMode mode,
                                       List<ToolInvocation> toolInvocations,
                                       boolean degraded) {
        String full = llmClient.chatStream(messages, new LlmClient.StreamListener() {
            @Override
            public void onThinking(String delta) {
                sink.onThinking(delta);
            }

            @Override
            public void onAnswer(String delta) {
                sink.onDelta(delta);
            }
        });
        memory.append(sessionId, ChatMessage.assistant(full));
        sink.onDone(sources);
        return new ChatResult(full, sources, mode, null, toolInvocations, degraded);
    }

    private ChatResult sendFallbackAnswer(ChatEventSink sink, String sessionId, String userMessage, ChatMode mode) {
        sink.onThinking("⚠️ 普通检索链路也发生异常，正在返回保守兜底答案");
        String fallback = buildFallbackAnswer(userMessage);
        streamAnswer(sink, fallback);
        memory.append(sessionId, ChatMessage.assistant(fallback));
        sink.onDone(List.of());
        return new ChatResult(fallback, List.of(), mode, null, List.of(), true);
    }

    private void streamAnswer(ChatEventSink sink, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        int step = 4;
        for (int i = 0; i < text.length(); i += step) {
            sink.onDelta(text.substring(i, Math.min(i + step, text.length())));
        }
    }

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

    private List<ToolSchema> toToolSchemas() {
        return toolRegistry.all().values().stream()
                .map(tool -> new ToolSchema(tool.name(), tool.description(), tool.parametersSchema()))
                .toList();
    }

    private List<ChatMessage> buildAgentMessages(String sessionId) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system("你是一个乐于助人的中文助手。需要查询知识库或计算时，请先调用对应工具再作答；最终回答简洁清晰，并在句末用 [n] 标注引用来源。"));
        messages.addAll(memory.load(sessionId, llmProperties.getMemoryRounds() * 2));
        return messages;
    }

    private String buildFallbackAnswer(String userMessage) {
        if (looksLikeMathQuestion(userMessage)) {
            return "抱歉，计算链路暂时不可用，我现在没法可靠给出这个结果。你可以稍后重试，或把表达式拆成更短的步骤再问我。";
        }
        if (userMessage != null && (userMessage.contains("文档") || userMessage.contains("文件") || userMessage.contains("说明") || userMessage.contains("资料"))) {
            return "抱歉，我刚刚在检索文档时遇到临时异常，暂时没法可靠定位到具体段落。你可以稍后重试，或直接告诉我文档名和关键词，我会优先帮你缩小范围。";
        }
        return "抱歉，当前检索与生成链路都出现了临时异常。为避免误导，我先不输出不确定结论。你可以稍后重试，或把问题缩短后再发一次。";
    }

    private boolean looksLikeMathQuestion(String userMessage) {
        return userMessage != null && userMessage.matches(".*[0-9][0-9+\\-*/(). ]*.*");
    }

    private static final class RecordingSink implements ChatEventSink {
        private final ChatEventSink delegate;
        private final StringBuilder answer = new StringBuilder();
        private List<RetrievedChunk> sources = List.of();

        private RecordingSink(ChatEventSink delegate) {
            this.delegate = delegate;
        }

        @Override
        public void onThinking(String text) {
            delegate.onThinking(text);
        }

        @Override
        public void onDelta(String text) {
            answer.append(text);
            delegate.onDelta(text);
        }

        @Override
        public void onDone(List<RetrievedChunk> sources) {
            this.sources = sources == null ? List.of() : List.copyOf(sources);
            delegate.onDone(this.sources);
        }

        @Override
        public void onError(String message) {
            delegate.onError(message);
        }

        private String answer() {
            return answer.toString();
        }

        private List<RetrievedChunk> sources() {
            return sources;
        }
    }
}

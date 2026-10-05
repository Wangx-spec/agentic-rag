package com.agenticrag.service;

import com.agenticrag.agent.AgentContext;
import com.agenticrag.agent.AgentLoop;
import com.agenticrag.agent.StepReporter;
import com.agenticrag.agent.SubQueryPlanner;
import com.agenticrag.config.LlmProperties;
import com.agenticrag.eval.trace.TraceRecorder;
import com.agenticrag.intent.Intent;
import com.agenticrag.intent.IntentClassifier;
import com.agenticrag.intent.QueryUnderstanding;
import com.agenticrag.intent.QueryUnderstandingService;
import com.agenticrag.llm.LlmClient;
import com.agenticrag.llm.dto.ChatMessage;
import com.agenticrag.llm.dto.ToolSchema;
import com.agenticrag.memory.ConversationMemory;
import com.agenticrag.memory.MemoryContextAssembler;
import com.agenticrag.memory.MemoryScope;
import com.agenticrag.multiagent.MultiAgentOrchestrator;
import com.agenticrag.rag.retrieve.HybridRetriever;
import com.agenticrag.rag.retrieve.RetrievedChunk;
import com.agenticrag.tool.ToolRegistry;
import com.agenticrag.tool.ToolVisibilityRouter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
public class ChatService {

    private static final double IMPLICIT_ROUTE_MIN_RRF_SCORE = 0.02;

    private static final long DEFAULT_USER_ID = 0L;

    private final LlmProperties llmProperties;
    private final LlmClient llmClient;
    private final ConversationMemory memory;
    private final HybridRetriever hybridRetriever;
    private final AgentLoop agentLoop;
    private final ToolRegistry toolRegistry;
    private final IntentClassifier intentClassifier;
    private final MultiAgentOrchestrator multiAgentOrchestrator;
    private final QueryUnderstandingService queryUnderstandingService;
    private final ToolVisibilityRouter toolVisibilityRouter;
    private final SubQueryPlanner subQueryPlanner;
    private final TraceRecorder traceRecorder;
    /**
     * M9 记忆编排门面（可选）：jdbc 模式提供「摘要+实体+长期记忆」读编排与写回调度；
     * 单测与其余记忆模式可为 null，行为退化为直连 memory 的既有语义。
     */
    private final MemoryContextAssembler memoryAssembler;

    public ChatService(LlmProperties llmProperties,
                       LlmClient llmClient,
                       ConversationMemory memory,
                       HybridRetriever hybridRetriever,
                       AgentLoop agentLoop,
                       ToolRegistry toolRegistry,
                       IntentClassifier intentClassifier,
                       MultiAgentOrchestrator multiAgentOrchestrator,
                       QueryUnderstandingService queryUnderstandingService,
                       @Autowired(required = false) MemoryContextAssembler memoryAssembler) {
        this(llmProperties, llmClient, memory, hybridRetriever, agentLoop, toolRegistry,
                intentClassifier, multiAgentOrchestrator, queryUnderstandingService, null, null, null, memoryAssembler);
    }

    @Autowired
    public ChatService(LlmProperties llmProperties,
                       LlmClient llmClient,
                       ConversationMemory memory,
                       HybridRetriever hybridRetriever,
                       AgentLoop agentLoop,
                       ToolRegistry toolRegistry,
                       IntentClassifier intentClassifier,
                       MultiAgentOrchestrator multiAgentOrchestrator,
                       QueryUnderstandingService queryUnderstandingService,
                       @Autowired(required = false) ToolVisibilityRouter toolVisibilityRouter,
                       @Autowired(required = false) SubQueryPlanner subQueryPlanner,
                       @Autowired(required = false) TraceRecorder traceRecorder,
                       @Autowired(required = false) MemoryContextAssembler memoryAssembler) {
        this.llmProperties = llmProperties;
        this.llmClient = llmClient;
        this.memory = memory;
        this.hybridRetriever = hybridRetriever;
        this.agentLoop = agentLoop;
        this.toolRegistry = toolRegistry;
        this.intentClassifier = intentClassifier;
        this.multiAgentOrchestrator = multiAgentOrchestrator;
        this.queryUnderstandingService = queryUnderstandingService;
        this.toolVisibilityRouter = toolVisibilityRouter;
        this.subQueryPlanner = subQueryPlanner;
        this.traceRecorder = traceRecorder;
        this.memoryAssembler = memoryAssembler;
    }

    /** 兼容入口：缺省系统用户（M8 登录态接入前的既有语义）。 */
    public ChatResult chat(String sessionId, String userMessage, ChatMode mode, ChatEventSink sink) {
        return chat(DEFAULT_USER_ID, sessionId, userMessage, mode, sink);
    }

    /**
     * M9/Wave E：真实 userId 入口（N3 隔离键；M8 鉴权落地后由 Controller 透传登录态）。
     */
    public ChatResult chat(long userId, String sessionId, String userMessage, ChatMode mode, ChatEventSink sink) {
        if (userMessage == null || userMessage.isBlank()) {
            throw new IllegalArgumentException("message 不能为空");
        }
        if (!llmProperties.isConfigured()) {
            throw new IllegalStateException("LLM 未配置，请设置 LLM_API_KEY 环境变量");
        }
        String actualSessionId = (sessionId == null || sessionId.isBlank()) ? "default" : sessionId;
        try {
            // M9/T9：请求链路内绑定记忆上下文，供记忆类工具（search_memory）与深层记忆调用取 userId
            MemoryScope.set(userId, actualSessionId);
            memory.append(userId, actualSessionId, ChatMessage.user(userMessage));
            return handleChat(sink, actualSessionId, userMessage, mode);
        } finally {
            MemoryScope.clear();
        }
    }

    /** 兼容入口：缺省系统用户。 */
    public void clearMemory(String sessionId) {
        clearMemory(DEFAULT_USER_ID, sessionId);
    }

    /** M9/Wave E：按 userId 清会话记忆（消息/摘要/提取计数）；跨会话资产不动。 */
    public void clearMemory(long userId, String sessionId) {
        if (sessionId != null && !sessionId.isBlank()) {
            memory.clear(userId, sessionId);
            if (memoryAssembler != null) {
                // 联动清滚动摘要与提取轮数计数；实体画像/长期记忆为跨会话资产不随会话清除
                memoryAssembler.clearSessionMemory(userId, sessionId);
            }
        }
    }

    private ChatResult handleChat(ChatEventSink sink, String sessionId, String userMessage, ChatMode mode) {
        try {
            return switch (mode) {
                case AGENT -> runAgentSafely(sink, sessionId, userMessage);
                case RAG -> runRag(sink, sessionId, userMessage);
                case PLAIN -> runPlain(sink, sessionId, userMessage);
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
            case CHAT, OFF_TOPIC -> withRoutedIntent(runPlain(sink, sessionId, userMessage), result.intent());
            case KB_QA -> withRoutedIntent(runRag(sink, sessionId, userMessage), result.intent());
            case MULTI_TASK -> withRoutedIntent(runMultiAgentSafely(sink, sessionId, userMessage), result.intent());
            case TOOL_TASK, DATA_ANALYSIS -> withRoutedIntent(runAgentSafely(sink, sessionId, userMessage), result.intent());
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
            return runPlain(sink, sessionId, userMessage);
        }
        return runRagWithRetrieved(sink, sessionId, userMessage, relevant);
    }

    private ChatResult runAgentSafely(ChatEventSink sink, String sessionId, String userMessage) {
        try {
            return runAgent(sink, sessionId, userMessage);
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

    private ChatResult runPlain(ChatEventSink sink, String sessionId, String userMessage) {
        List<ChatMessage> messages = buildMessages(sessionId, userMessage, List.of());
        return streamLlmAnswer(sink, sessionId, messages, List.of(), ChatMode.PLAIN, List.of(), false);
    }

    private ChatResult runRag(ChatEventSink sink, String sessionId, String userMessage) {
        List<RetrievedChunk> retrieved = hybridRetriever.retrieve(userMessage);
        return runRagWithRetrieved(sink, sessionId, userMessage, retrieved);
    }

    private ChatResult runRagWithRetrieved(ChatEventSink sink, String sessionId, String userMessage,
                                           List<RetrievedChunk> relevant) {
        List<RetrievedChunk> safeRelevant = relevant == null ? List.of() : relevant;
        List<ChatMessage> messages = buildMessages(sessionId, userMessage, safeRelevant);
        return streamLlmAnswer(sink, sessionId, messages, safeRelevant, ChatMode.RAG, List.of(), false);
    }

    private ChatResult runAgent(ChatEventSink sink, String sessionId, String userMessage) {
        List<ToolInvocation> toolInvocations = new ArrayList<>();
        Optional<QueryUnderstanding> understanding = understandForAgent(userMessage);
        List<ToolSchema> visibleTools = toToolSchemas(understanding);
        List<ChatMessage> agentMessages = buildAgentMessages(sessionId, userMessage);
        if (subQueryPlanner != null) {
            subQueryPlanner.buildPlanBlock(understanding)
                    .ifPresent(value -> agentMessages.add(ChatMessage.system(value)));
        }
        understanding.filter(value -> hasRetrievalGuidance(value, visibleTools))
                .ifPresent(value -> agentMessages.add(ChatMessage.system(buildRetrievalGuidance(value))));
        AgentContext ctx = new AgentContext(agentMessages, visibleTools, llmProperties.getMaxAgentRounds(), userMessage);
        ctx.setRoutedIntent(understanding.map(QueryUnderstanding::intent).orElse(Intent.UNKNOWN));
        if (traceRecorder != null) {
            traceRecorder.recordIntent(0, ctx.getRoutedIntent().name());
        }
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

            @Override
            public void onTable(java.util.Map<String, Object> payload) {
                sink.onTable(payload);
            }

            @Override
            public void onEvidence(com.agenticrag.agent.EvidenceRegistry.Evidence evidence) {
                sink.onEvidence(evidence);
            }
        };
        String finalAnswer = agentLoop.run(ctx, reporter);
        streamAnswer(sink, finalAnswer);
        memory.append(currentUserId(), sessionId, ChatMessage.assistant(finalAnswer));
        completeTurn(sessionId);
        sink.onDone(ctx.getSources());
        return new ChatResult(finalAnswer, ctx.getSources(), ChatMode.AGENT,
                understanding.map(QueryUnderstanding::intent).orElse(null),
                List.copyOf(toolInvocations), false);
    }

    private Optional<QueryUnderstanding> understandForAgent(String userMessage) {
        if (queryUnderstandingService == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(queryUnderstandingService.understand(userMessage)).orElse(Optional.empty());
    }

    private boolean hasRetrievalGuidance(QueryUnderstanding understanding, List<ToolSchema> visibleTools) {
        boolean retrievalVisible = toolVisibilityRouter == null
                || visibleTools.stream().anyMatch(tool -> "search_knowledge_base".equals(tool.name()));
        return retrievalVisible && (understanding.normalizedQuery() != null || !understanding.subQueries().isEmpty());
    }

    private String buildRetrievalGuidance(QueryUnderstanding understanding) {
        StringBuilder guidance = new StringBuilder("检索引导：建议优先使用以下查询调用 search_knowledge_base：");
        if (understanding.normalizedQuery() != null) {
            guidance.append("\"").append(understanding.normalizedQuery()).append("\"");
        }
        if (!understanding.subQueries().isEmpty()) {
            guidance.append("。该问题包含多个子问题，建议逐一检索：")
                    .append(String.join("；", understanding.subQueries()));
        }
        guidance.append("。最终回答必须对齐用户原始问题。");
        return guidance.toString();
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
        memory.append(currentUserId(), sessionId, ChatMessage.assistant(full));
        completeTurn(sessionId);
        sink.onDone(sources);
        return new ChatResult(full, sources, mode, null, toolInvocations, degraded);
    }

    private ChatResult sendFallbackAnswer(ChatEventSink sink, String sessionId, String userMessage, ChatMode mode) {
        sink.onThinking("⚠️ 普通检索链路也发生异常，正在返回保守兜底答案");
        String fallback = buildFallbackAnswer(userMessage);
        streamAnswer(sink, fallback);
        memory.append(currentUserId(), sessionId, ChatMessage.assistant(fallback));
        completeTurn(sessionId);
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

    private List<ChatMessage> buildMessages(String sessionId, String userMessage, List<RetrievedChunk> retrieved) {
        List<ChatMessage> messages = new ArrayList<>();
        if (retrieved == null || retrieved.isEmpty()) {
            messages.add(new ChatMessage("system", "你是一个乐于助人的中文助手，回答简洁清晰。"));
        } else {
            StringBuilder context = new StringBuilder();
            context.append("你是一个基于给定上下文回答问题的中文助手。回答规则：\n"
                    + "1. 优先利用给定上下文作答，句末以 [n] 标注引用来源；\n"
                    + "2. 关键细节（编号、日期、数值、阈值、名称等）必须按原文精确复述，不得省略或改写；\n"
                    + "3. 若问题包含多个子项，必须逐项回答，不可遗漏任何一项；\n"
                    + "4. 若上下文信息不完整，基于现有上下文尽力作答，对推测部分以（不确定）标注，不要直接拒答。\n\n");
            for (RetrievedChunk chunk : retrieved) {
                context.append("[").append(chunk.rank()).append("] ")
                        .append(chunk.docName()).append("：")
                        .append(chunk.content()).append("\n\n");
            }
            messages.add(ChatMessage.system(context.toString()));
        }
        appendMemorySection(messages, sessionId, userMessage);
        appendHistory(messages, sessionId);
        return messages;
    }

    private List<ChatMessage> buildAgentMessages(String sessionId, String userMessage) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system("你是一个乐于助人的中文助手。需要查询知识库或计算时，请先调用对应工具再作答。"
                + "最终回答规则：简洁清晰，句末以 [n] 标注引用来源；"
                + "关键细节（编号、日期、数值等）按原文精确复述；"
                + "若问题含多个子项，逐项回答不可遗漏；"
                + "信息不完整时基于现有内容尽力作答，对推测部分以（不确定）标注。"));
        appendMemorySection(messages, sessionId, userMessage);
        appendHistory(messages, sessionId);
        return messages;
    }

    /**
     * 注入 M9 记忆片段（实体画像 + 长期记忆）：置于系统 prompt 之后、会话历史之前。
     * assembler 缺失或片段为空则跳过，不影响既有链路（N1 fail-open）。
     */
    private void appendMemorySection(List<ChatMessage> messages, String sessionId, String userMessage) {
        if (memoryAssembler == null) {
            return;
        }
        try {
            String section = memoryAssembler.buildMemorySection(currentUserId(), userMessage);
            if (section != null && !section.isBlank()) {
                messages.add(1, ChatMessage.system(section));
            }
        } catch (Exception e) {
            log.warn("记忆片段注入失败，本轮跳过: sessionId={}", sessionId, e);
        }
    }

    /**
     * 加载会话历史：优先走 assembler 的「滚动摘要 + 窗口原文」读编排；
     * assembler 缺失时退化为既有 memory.load 语义（窗口原文）。
     */
    private void appendHistory(List<ChatMessage> messages, String sessionId) {
        if (memoryAssembler != null) {
            messages.addAll(memoryAssembler.loadHistory(currentUserId(), sessionId, llmProperties.getMemoryRounds()));
        } else {
            messages.addAll(memory.load(currentUserId(), sessionId, llmProperties.getMemoryRounds() * 2));
        }
    }

    /**
     * M9/Wave E：请求线程内取 MemoryScope 绑定的当前 userId；
     * 非请求线程（scope 未绑定）回落系统用户 0，保持既有缺省语义兼容。
     */
    private long currentUserId() {
        MemoryScope.Context ctx = MemoryScope.current();
        return ctx == null ? DEFAULT_USER_ID : ctx.userId();
    }

    /**
     * 一轮回答完成后的记忆写回调度：摘要压缩（异步）+ 提取计数（O(1)）。
     * 全程 fail-open；user 消息与 assistant 消息均已在 memory 中。
     */
    private void completeTurn(String sessionId) {
        if (memoryAssembler != null) {
            try {
                memoryAssembler.onTurnCompleted(currentUserId(), sessionId);
            } catch (Exception e) {
                log.warn("记忆写回调度失败，跳过: sessionId={}", sessionId, e);
            }
        }
    }

    private List<ToolSchema> toToolSchemas(Optional<QueryUnderstanding> understanding) {
        if (toolVisibilityRouter != null) {
            return toolVisibilityRouter.visibleTools(understanding);
        }
        return toolRegistry.all().values().stream()
                .map(tool -> new ToolSchema(tool.name(), tool.description(), tool.parametersSchema()))
                .toList();
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

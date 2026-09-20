package com.agenticrag.agent;

import com.agenticrag.config.LlmProperties;
import com.agenticrag.config.RagProperties;
import com.agenticrag.llm.LlmClient;
import com.agenticrag.llm.dto.ChatMessage;
import com.agenticrag.llm.dto.LlmResponse;
import com.agenticrag.llm.dto.ToolCall;
import com.agenticrag.llm.dto.ToolSchema;
import com.agenticrag.rag.retrieve.RerankClient;
import com.agenticrag.rag.retrieve.RerankResult;
import com.agenticrag.rag.retrieve.RetrievedChunk;
import com.agenticrag.tool.Tool;
import com.agenticrag.tool.ToolRegistry;
import com.agenticrag.tool.ToolSchemaValidator;
import com.agenticrag.tool.tools.SearchKnowledgeBaseTool;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Component
public class AgentLoop {

    private final LlmClient llmClient;
    private final ToolRegistry toolRegistry;
    private final LlmProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ToolSchemaValidator toolSchemaValidator;
    private final RagProperties ragProperties;
    private final RerankClient rerankClient;

    public AgentLoop(LlmClient llmClient,
                     ToolRegistry toolRegistry,
                     LlmProperties properties,
                     ToolSchemaValidator toolSchemaValidator,
                     RagProperties ragProperties,
                     @Autowired(required = false) RerankClient rerankClient) {
        this.llmClient = llmClient;
        this.toolRegistry = toolRegistry;
        this.properties = properties;
        this.toolSchemaValidator = toolSchemaValidator;
        this.ragProperties = ragProperties;
        this.rerankClient = rerankClient;
    }

    /**
     * 运行智能循环
     * @param ctx 智能循环上下文
     * @param reporter 智能循环步骤报告器
     * @return 最终回答
     */
    public String run(AgentContext ctx, StepReporter reporter) {

        while (!ctx.isMaxRoundsReached()) {
            List<ToolSchema> tools = ctx.getCurrentRound() == ctx.getMaxRounds() - 1 ? List.of() : ctx.getAvailableTools();
            LlmResponse response = llmClient.chatWithTools(ctx.getMessages(), tools);

            if (response.toolCalls() == null || response.toolCalls().isEmpty()) {
                ctx.recordState(AgentState.FINAL);
                ctx.addMessage(ChatMessage.assistant(response.content()));
                reporter.onFinal("生成最终回答");
                String answer = sanitizeFinalAnswer(ctx, response.content());
                pruneCitations(ctx);
                return answer;
            }

            ToolCall toolCall = response.toolCalls().get(0);
            ctx.recordState(AgentState.THINKING);
            reporter.onThinking(toolCall.name());

            ctx.recordState(AgentState.ACTING);
            reporter.onActing(toolCall.name(), toolCall.argumentsJson());

            String result = executeTool(ctx, toolCall);
            ctx.recordState(AgentState.OBSERVING);
            reporter.onObserving(summarizeResult(result));

            ctx.addMessage(ChatMessage.assistantWithToolCalls(response.content(), List.of(toolCall)));
            ctx.addMessage(ChatMessage.tool(toolCall.id(), result));
            ctx.incrementRound();
        }
        ctx.recordState(AgentState.FINAL);
        reporter.onFinal("达到最大轮次，强制输出");
        try {
            String finalContent = llmClient.chat(ctx.getMessages());
            ctx.addMessage(ChatMessage.assistant(finalContent));
            String answer = sanitizeFinalAnswer(ctx, finalContent);
            pruneCitations(ctx);
            return answer;
        } catch (Exception e) {
            log.warn("强制 FINAL 时 LLM 调用失败", e);
            return "抱歉，处理超时，请简化您的问题后重试。";
        }
    }

    /**
     * 检测终答是否泄漏工具调用标记，若泄漏则用累积 sources 重新生成干净答案。
     */
    private String sanitizeFinalAnswer(AgentContext ctx, String rawAnswer) {
        if (rawAnswer == null || !rawAnswer.contains("<tool_call")) {
            return rawAnswer;
        }

        log.warn("检测到终答泄漏（含 <tool_call 标记），触发 RAG 合成降级");

        List<RetrievedChunk> accumulated = ctx.getSources();
        if (accumulated == null || accumulated.isEmpty()) {
            log.warn("累积 sources 为空，无法降级，返回原始终答");
            return rawAnswer;
        }

        String query = ctx.getOriginalQuery();
        if (query == null || query.isBlank()) {
            log.warn("原始查询为空，无法降级，返回原始终答");
            return rawAnswer;
        }

        List<RetrievedChunk> deduped = deduplicateByDocumentId(accumulated);
        int topN = citationTopN();
        List<RetrievedChunk> pruned = rerankPruneIfEnabled(query, deduped, topN);
        // 引用集同步替换为合成实际使用的剪枝集（判分只看终答实际依据的文档）
        ctx.replaceSources(pruned);

        try {
            return synthesizeRagAnswer(query, pruned);
        } catch (Exception e) {
            log.warn("RAG 合成失败", e);
            return "抱歉，处理失败，请重试。";
        }
    }

    /**
     * 终答引用裁剪：累积 sources 超过 citationTopN 时按 rerank 精排裁剪，
     * 使引用集=终答实际依据的文档集，而非多轮检索累积全集。
     * fail-open：rerank 不可用/失败时保留累积顺序前 N 个。
     */
    private void pruneCitations(AgentContext ctx) {
        List<RetrievedChunk> accumulated = ctx.getSources();
        int topN = citationTopN();
        if (accumulated.size() <= topN) {
            return;
        }
        String query = ctx.getOriginalQuery();
        List<RetrievedChunk> pruned = (query == null || query.isBlank())
                ? accumulated.subList(0, topN)
                : rerankPruneIfEnabled(query, accumulated, topN);
        ctx.replaceSources(pruned);
        log.info("终答引用裁剪：{} → {} 个文档", accumulated.size(), pruned.size());
    }

    private int citationTopN() {
        return ragProperties.getAgent() != null ? ragProperties.getAgent().getCitationTopN() : 8;
    }

    private List<RetrievedChunk> deduplicateByDocumentId(List<RetrievedChunk> chunks) {
        Map<Long, RetrievedChunk> seen = new LinkedHashMap<>();
        for (RetrievedChunk chunk : chunks) {
            seen.putIfAbsent(chunk.documentId(), chunk);
        }
        return new ArrayList<>(seen.values());
    }

    private List<RetrievedChunk> rerankPruneIfEnabled(String query, List<RetrievedChunk> chunks, int topN) {
        boolean rerankEnabled = ragProperties.getRetrieval() != null
                && ragProperties.getRetrieval().getRerank() != null
                && ragProperties.getRetrieval().getRerank().isEnabled()
                && rerankClient != null;

        if (!rerankEnabled || chunks.isEmpty()) {
            return chunks.subList(0, Math.min(topN, chunks.size()));
        }

        List<String> docs = chunks.stream().map(RetrievedChunk::content).toList();
        Optional<List<RerankResult>> rerankOpt = rerankClient.rerank(query, docs, topN,
                ragProperties.getRetrieval().getRerank().getModel());

        if (rerankOpt.isPresent()) {
            List<RetrievedChunk> result = new ArrayList<>();
            for (RerankResult r : rerankOpt.get()) {
                if (r.index() >= 0 && r.index() < chunks.size()) {
                    result.add(chunks.get(r.index()));
                }
            }
            return result;
        } else {
            log.warn("Agent 终答降级中的 rerank 失败，保留原序 top {}", topN);
            return chunks.subList(0, Math.min(topN, chunks.size()));
        }
    }

    private String synthesizeRagAnswer(String query, List<RetrievedChunk> chunks) {
        StringBuilder context = new StringBuilder();
        context.append("你是一个基于给定上下文回答问题的中文助手。回答规则：\n"
                + "1. 优先利用给定上下文作答，句末以 [n] 标注引用来源；\n"
                + "2. 关键细节（编号、日期、数值、阈值、名称等）必须按原文精确复述，不得省略或改写；\n"
                + "3. 若问题包含多个子项，必须逐项回答，不可遗漏任何一项；\n"
                + "4. 若上下文信息不完整，基于现有上下文尽力作答，对推测部分以（不确定）标注，不要直接拒答。\n\n");

        for (int i = 0; i < chunks.size(); i++) {
            RetrievedChunk chunk = chunks.get(i);
            context.append("[").append(i + 1).append("] ")
                    .append(chunk.docName()).append("：")
                    .append(chunk.content()).append("\n\n");
        }

        List<ChatMessage> messages = List.of(
                ChatMessage.system(context.toString()),
                ChatMessage.user(query)
        );

        return llmClient.chat(messages);
    }

    /**
     * 执行工具调用
     * @param toolCall 工具调用
     * @return 工具执行结果
     */
    private String executeTool(AgentContext ctx, ToolCall toolCall) {
        Optional<Tool> toolOpt = toolRegistry.find(toolCall.name());
        if (toolOpt.isEmpty()) {
            return "错误：未找到工具 \"" + toolCall.name() + "\"，可用工具：" + toolRegistry.all().keySet();
        }
        try {
            Map<String, Object> args = objectMapper.readValue(
                    toolCall.argumentsJson(),
                    new TypeReference<Map<String, Object>>() {}
            );
            Tool tool = toolOpt.get();
            ToolSchemaValidator.ValidationResult validationResult = toolSchemaValidator.validate(tool.parametersSchema(), args);
            if (!validationResult.valid()) {
                return "错误：" + validationResult.message();
            }
            if (tool instanceof SearchKnowledgeBaseTool kbTool) {
                Object queryObj = args.get("query");
                if (queryObj == null){
                    return "错误：查询参数不能为空";
                }
                String query = queryObj.toString();
                List<RetrievedChunk> chunks = kbTool.search(query);
                ctx.addSources(chunks);
                return kbTool.render(chunks);
            }
            return tool.execute(args);
        } catch (Exception e) {
            log.warn("工具 {} 执行失败", toolCall.name(), e);
            return "工具参数解析失败：" + e.getMessage();
        }
    }

    /**
     * 摘要工具执行结果
     * @param result 工具执行结果
     * @return 摘要后的结果
     */
    private static String summarizeResult(String result) {
        if (result == null || result.isBlank()) {
            return "工具返回空结果";
        }
        int maxLen = 100;
        return result.length() > maxLen ? result.substring(0, maxLen) + "..." : result;
    }

}

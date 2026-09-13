package com.agenticrag.agent;

import com.agenticrag.config.LlmProperties;
import com.agenticrag.llm.LlmClient;
import com.agenticrag.llm.dto.ChatMessage;
import com.agenticrag.llm.dto.LlmResponse;
import com.agenticrag.llm.dto.ToolCall;
import com.agenticrag.llm.dto.ToolSchema;
import com.agenticrag.rag.retrieve.RetrievedChunk;
import com.agenticrag.tool.Tool;
import com.agenticrag.tool.ToolRegistry;
import com.agenticrag.tool.ToolSchemaValidator;
import com.agenticrag.tool.tools.SearchKnowledgeBaseTool;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class AgentLoop {

    private final LlmClient llmClient;
    private final ToolRegistry toolRegistry;
    private final LlmProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ToolSchemaValidator toolSchemaValidator;

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
                return response.content();
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
            return finalContent;
        } catch (Exception e) {
            log.warn("强制 FINAL 时 LLM 调用失败", e);
            return "抱歉，处理超时，请简化您的问题后重试。";
        }
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

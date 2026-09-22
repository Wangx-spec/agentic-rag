package com.agenticrag.intent;

import com.agenticrag.config.RagProperties;
import com.agenticrag.llm.LlmClient;
import com.agenticrag.llm.dto.ChatMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 在 AGENT ReAct 循环前，将用户问题转换为检索友好的规划建议。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QueryUnderstandingService {

    private static final String SYSTEM_PROMPT = """
            你是知识库 Agent 的查询理解助手。请判断用户问题的意图，并生成给检索工具使用的规范查询。

            意图只能是以下五类之一：
            - CHAT：日常闲聊、打招呼、问候、情感交流，不需要知识库或工具
            - KB_QA：需要查询知识库文档才能回答的问题，涉及领域知识、文档内容或技术细节
            - MULTI_TASK：包含多个彼此独立、可分别作答的子问题，需要拆解后汇总
            - TOOL_TASK：需要调用工具完成的任务，如数学计算、数据查询或 API 调用
            - OFF_TOPIC：明显偏离主题、无意义、恶意或无法处理的输入

            normalized_query 是给知识库检索工具的建议：保留用户原意，补全术语、展开缩写，使用检索友好的表达，
            不超过原问题长度的 1.5 倍，不要改变用户真正要问的事情。
            仅 MULTI_TASK 需要输出 sub_queries，每个元素是一条可独立检索的子问题；其他意图输出空数组。
            只返回 JSON，不要输出 Markdown 或解释文字，格式：
            {"intent":"KB_QA","confidence":0.9,"normalized_query":"检索建议","sub_queries":[]}
            """;

    private final LlmClient llmClient;
    private final IntentProperties intentProperties;
    private final RagProperties ragProperties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public Optional<QueryUnderstanding> understand(String userMessage) {
        if (!ragProperties.getAgent().isQueryUnderstandingEnabled()
                || userMessage == null || userMessage.isBlank()) {
            return Optional.empty();
        }
        try {
            String response = llmClient.chat(List.of(
                    ChatMessage.system(SYSTEM_PROMPT),
                    ChatMessage.user(userMessage)
            ));
            Optional<QueryUnderstanding> result = parse(response);
            result.ifPresent(value -> log.info("查询理解完成：intent={}, normalizedQuery={}, subQueries={}",
                    value.intent(), value.normalizedQuery(), value.subQueries().size()));
            return result;
        } catch (Exception e) {
            log.debug("查询理解失败，跳过前置步骤：{}", e.getMessage());
            return Optional.empty();
        }
    }

    Optional<QueryUnderstanding> parse(String response) {
        try {
            String json = stripCodeFence(response);
            JsonNode node = objectMapper.readTree(json);
            String intentText = node.path("intent").asText("").trim().toUpperCase();
            if (intentText.isBlank()
                    || !node.has("confidence")
                    || !node.has("normalized_query")
                    || !node.has("sub_queries")) {
                return Optional.empty();
            }
            Intent intent = Intent.valueOf(intentText);
            if (intent == Intent.UNKNOWN) {
                return Optional.empty();
            }
            double confidence = node.path("confidence").asDouble(-1.0);
            if (confidence < intentProperties.getConfidenceThreshold() || confidence > 1.0) {
                return Optional.empty();
            }

            String normalizedQuery = node.path("normalized_query").asText("").trim();
            List<String> subQueries = new ArrayList<>();
            JsonNode subQueryNode = node.path("sub_queries");
            if (subQueryNode.isArray()) {
                for (JsonNode item : subQueryNode) {
                    String value = item.asText("").trim();
                    if (!value.isBlank()) {
                        subQueries.add(value);
                    }
                }
            }
            if (intent == Intent.MULTI_TASK && subQueries.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new QueryUnderstanding(
                    intent,
                    confidence,
                    normalizedQuery.isBlank() ? null : normalizedQuery,
                    subQueries
            ));
        } catch (Exception e) {
            log.debug("查询理解响应解析失败，跳过前置步骤");
            return Optional.empty();
        }
    }

    private String stripCodeFence(String response) {
        if (response == null) {
            return "";
        }
        String json = response.trim();
        if (json.startsWith("```json")) {
            json = json.substring("```json".length()).trim();
        } else if (json.startsWith("```")) {
            json = json.substring(3).trim();
        }
        if (json.endsWith("```")) {
            json = json.substring(0, json.length() - 3).trim();
        }
        return json;
    }
}

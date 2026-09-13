package com.agenticrag.intent;

import com.agenticrag.config.LlmProperties;
import com.agenticrag.llm.LlmClient;
import com.agenticrag.llm.dto.ChatMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class IntentClassifier {

    private static final String SYSTEM_PROMPT = """
            你是一个意图分类助手，根据用户问题判断其意图类型，并返回纯 JSON 格式结果。

            意图定义：
            - CHAT: 日常闲聊、打招呼、问候、情感交流等不需要查询知识库或工具的对话
            - KB_QA: 需要查询知识库文档才能回答的问题，通常涉及特定领域知识、文档内容、技术细节
            - MULTI_TASK: 明确包含多个彼此独立、可分别作答的子问题，适合先拆解再汇总
            - TOOL_TASK: 需要调用工具完成的任务，如数学计算、数据查询、API调用等
            - OFF_TOPIC: 明显偏离主题、无意义、恶意或无法处理的输入
            
            示例：
            用户："你好" → {"intent": "CHAT", "confidence": 0.95}
            用户："文档里提到的 RAG 是什么意思？" → {"intent": "KB_QA", "confidence": 0.9}
            用户："A 是什么？A 和 B 有什么区别？" → {"intent": "MULTI_TASK", "confidence": 0.9}
            用户："什么是 RAG？" → {"intent": "KB_QA", "confidence": 0.9}
            用户："计算 123 * 456" → {"intent": "TOOL_TASK", "confidence": 0.95}
            用户："asdfghjkl" → {"intent": "OFF_TOPIC", "confidence": 0.8}
            
            要求：
            1. 仅返回 JSON，格式为 {"intent": "类型", "confidence": 浮点数}
            2. confidence 取值 0-1，表示分类置信度
            3. 不要输出任何解释文字
            4. 仅当问题明确包含多个独立子问题时才判 MULTI_TASK；不确定时倾向单链路（KB_QA / CHAT / TOOL_TASK）
            """;
    
    private final IntentProperties intentProperties;
    private final LlmProperties llmProperties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 对用户输入问题进行意图分类（带两级降级）
     */
    public IntentResult classify(String userMessage) {
        if (!intentProperties.isEnabled()) {
            return new IntentResult(Intent.UNKNOWN, 0.0);
        }

        try {
            LlmProperties intentLlmProperties = buildIntentLlmProperties();
            if (!intentLlmProperties.isConfigured()
                    || intentLlmProperties.getBaseUrl() == null
                    || intentLlmProperties.getBaseUrl().isBlank()
                    || intentLlmProperties.getChatModel() == null
                    || intentLlmProperties.getChatModel().isBlank()) {
                log.warn("意图识别配置不完整，降级返回 UNKNOWN");
                return new IntentResult(Intent.UNKNOWN, 0.0);
            }
            log.info("Intent classifier request: dedicated={}, baseUrl={}, model={}, timeoutSeconds={}",
                    hasDedicatedIntentConfig(),
                    intentLlmProperties.getBaseUrl(),
                    intentLlmProperties.getChatModel(),
                    intentLlmProperties.getTimeoutSeconds());
            String responseJson = callLlm(userMessage, intentLlmProperties);
            return parseIntent(responseJson);
        } catch (Exception e) {
            log.warn("意图识别首次调用失败（专用模型），准备降级: {}", e.getMessage());
            // 一级降级：如果用的是专用模型，回退到主模型
            if (hasDedicatedIntentConfig()) {
                try {
                    log.info("意图识别降级到主模型：baseUrl={}, model={}", llmProperties.getBaseUrl(), llmProperties.getChatModel());
                    String responseJson = callLlm(userMessage, llmProperties);
                    IntentResult result = parseIntent(responseJson);
                    log.info("意图识别主模型降级成功：intent={}, confidence={}", result.intent(), result.confidence());
                    return result;
                } catch (Exception fallbackEx) {
                    log.warn("意图识别主模型降级也失败，返回 UNKNOWN: {}", fallbackEx.getMessage());
                }
            }
            // 二级降级：返回 UNKNOWN
            return new IntentResult(Intent.UNKNOWN, 0.0);
        }
    }

    private boolean hasDedicatedIntentConfig() {
        return !intentProperties.getBaseUrl().isBlank()
                || !intentProperties.getModel().isBlank()
                || !intentProperties.getApiKey().isBlank();
    }

    /**
     * 调用意图识别模型并返回原始文本
     */
    protected String callLlm(String userMessage, LlmProperties intentLlmProperties) {
        LlmClient intentLlmClient = new LlmClient(intentLlmProperties);
        return intentLlmClient.chat(List.of(
                ChatMessage.system(SYSTEM_PROMPT),
                ChatMessage.user(userMessage)
        ));
    }

    /**
     * 解析意图识别结果
     */
    IntentResult parseIntent(String responseJson) {
        try {
            responseJson = responseJson.trim();

            if (responseJson.startsWith("```json")) {
                responseJson = responseJson.substring("```json".length()).trim();
            }
            if (responseJson.startsWith("```")) {
                responseJson = responseJson.substring("```".length()).trim();
            }
            if (responseJson.endsWith("```")) {
                responseJson = responseJson.substring(0, responseJson.length() - 3).trim();
            }

            JsonNode node = objectMapper.readTree(responseJson);
            String intentStr = node.path("intent").asText("").toUpperCase();
            double confidence = node.path("confidence").asDouble(0.0);

            Intent intent = Intent.valueOf(intentStr);

            if (confidence < intentProperties.getConfidenceThreshold()) {
                log.debug("意图分类置信度过低 ({} < {})，降级返回 UNKNOWN", confidence, intentProperties.getConfidenceThreshold());
                return new IntentResult(Intent.UNKNOWN, confidence);
            }

            return new IntentResult(intent, confidence);

        } catch (Exception e) {
            log.warn("意图识别响应解析失败，降级返回 UNKNOWN: response={}", responseJson, e);
            return new IntentResult(Intent.UNKNOWN, 0.0);
        }
    }

    /**
     * 构造意图识别使用的 LLM 配置，空值回退主 LLM 配置
     */
    private LlmProperties buildIntentLlmProperties() {
        LlmProperties properties = new LlmProperties();
        properties.setBaseUrl(intentProperties.getBaseUrl().isBlank() ? llmProperties.getBaseUrl() : intentProperties.getBaseUrl());
        properties.setChatModel(intentProperties.getModel().isBlank() ? llmProperties.getChatModel() : intentProperties.getModel());
        properties.setApiKey(intentProperties.getApiKey().isBlank() ? llmProperties.getApiKey() : intentProperties.getApiKey());
        properties.setTimeoutSeconds(intentProperties.getTimeoutSeconds());
        return properties;
    }

    /**
     * 意图分类结果
     */
    public record IntentResult(Intent intent, double confidence) {
    }
}

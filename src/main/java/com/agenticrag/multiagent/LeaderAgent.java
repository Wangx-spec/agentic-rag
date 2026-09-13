package com.agenticrag.multiagent;

import com.agenticrag.llm.LlmClient;
import com.agenticrag.llm.dto.ChatMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;


/**
 * Leader：把复合问题拆解为子问题列表
 * <p>
 * 拆解是增强不是依赖：LLM 调用失败或输出畸形时降级为单元素列表（原问题），
 * 编排器据此判定 size &lt;= 1 → 回退普通 RAG 链路，用户无感。
 * <p>
 * 上限 4 个子问题：多 Agent 链路成本约为单链路的 3-5 倍（N 次检索 + N 次子结论 + 1 次汇总），
 * 截断在成本与覆盖率之间取平衡。
 */
@Slf4j 
@Component 
@RequiredArgsConstructor 
public class LeaderAgent {
    
    /** 子问题数量上限，超出截断 */
    static final int MAX_SUB_QUESTIONS = 4;

    private static final String PLAN_PROMPT_TEMPLATE = """
            你是一个问题拆解助手。判断用户问题是否包含多个可独立回答的子问题，若包含则拆解为子问题列表。

            拆解要求：
            1. 每个子问题必须自包含：脱离原问题也能被理解，不出现「它」「前者」「上述」等指代
            2. 子问题之间尽量正交，避免内容重复
            3. 最多拆解 %d 个子问题；若原问题本身就是单一问题，返回只含原问题的单元素列表
            4. 不要回答问题，只做拆解

            示例：
            用户："文档里 A 是什么？A 和 B 有什么区别？" → {"subQuestions": ["文档里 A 是什么？", "文档里 A 和 B 有什么区别？"]}
            用户："什么是 RAG？" → {"subQuestions": ["什么是 RAG？"]}

            仅返回 JSON，格式为 {"subQuestions": ["...", "..."]}，不要输出任何解释文字或代码块标记。
            """;
    
    private final LlmClient llmClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 拆解复合问题为子问题列表
     *
     * @return 子问题列表（1..MAX_SUB_QUESTIONS 个）；无法拆解或调用失败时返回单元素列表
     */
    public List<String> plan(String question) {
        if (question == null || question.isBlank()) {
            return fallbackToSingle(question);
        }

        try {
            String response = llmClient.chat(List.of(
                    ChatMessage.system(buildPlanPrompt(question)),
                    ChatMessage.user(question)
            ));
            List<String> subQuestions = parsePlanResponse(response);
            if (subQuestions.isEmpty()) {
                log.warn("问题拆解结果为空，降级为单问题: response={}", response);
                return fallbackToSingle(question);
            }
            if (subQuestions.size() > MAX_SUB_QUESTIONS) {
                log.info("子问题数 {} 超上限，截断为 {}", subQuestions.size(), MAX_SUB_QUESTIONS);
                subQuestions = subQuestions.subList(0, MAX_SUB_QUESTIONS);
            }
            log.info("问题拆解完成: {} → {}", question, subQuestions);
            return subQuestions;           
        } catch (Exception e) {
            log.warn("问题拆解失败，降级为单问题: question={}", question, e);
            return fallbackToSingle(question);
        }
    }

    /**
     * 解析 LLM 输出的拆解结果；解析失败返回空列表（由调用方降级）
     * <p>
     * 包级可见以便单测直接覆盖畸形输入，对齐 IntentClassifier#parseIntent
     */
    List<String> parsePlanResponse(String llmResponse) {
        if (llmResponse == null || llmResponse.isBlank()) {
            return List.of();
        }
        try {
            JsonNode root = objectMapper.readTree(stripCodeFence(llmResponse));
            JsonNode arrayNode = root.isArray() ? root : root.path("subQuestions");
            if (!arrayNode.isArray()) {
                return List.of();
            }
            Set<String> deduped = new LinkedHashSet<>();
            for (JsonNode node : arrayNode) {
                String subQuestion = node.asText("").trim();
                if (!subQuestion.isEmpty()) {
                    deduped.add(subQuestion);
                }
            }
            return new ArrayList<>(deduped);  
        } catch (Exception e) {
            log.warn("拆解响应解析失败: response={}", llmResponse, e);
            return List.of();
        }
    }
    
    private String buildPlanPrompt(String question) {
        return PLAN_PROMPT_TEMPLATE.formatted(MAX_SUB_QUESTIONS);
    }

    private List<String> fallbackToSingle(String question) {
        return List.of(question == null ? "" : question);
    }

    /**
     * 去除 LLM 可能包裹的 markdown 代码块标记
     */
    private String stripCodeFence(String raw) {
        String text = raw.trim();
        if (text.startsWith("```json")) {
            text = text.substring("```json".length()).trim();
        } else if (text.startsWith("```")) {
            text = text.substring("```".length()).trim();
        }
        if (text.endsWith("```")) {
            text = text.substring(0, text.length() - 3).trim();
        }
        return text;
    }

}

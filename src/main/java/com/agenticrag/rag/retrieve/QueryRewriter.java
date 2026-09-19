package com.agenticrag.rag.retrieve;

import com.agenticrag.config.RagProperties;
import com.agenticrag.llm.LlmClient;
import com.agenticrag.llm.dto.ChatMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 查询改写器（M6 Phase 3.1）：
 * 用 LLM 生成 1 个改写查询，与原查询各自独立检索后 RRF 融合（多查询扩展）。
 * - 只对 token 估算数超过阈值的查询改写（短查询无需，控制 LLM 成本）
 * - 任何 LLM 异常 fail-open：降级只用原查询，绝不阻断检索主链路
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QueryRewriter {

    private static final int MAX_REWRITE_LENGTH = 400;

    private static final String SYSTEM_PROMPT = """
            你是检索优化专家。针对给定的用户问题，生成 1 个改写版本，要求：
            1. 保留核心意图，用同义词/相关术语替换原词
            2. 适当扩展背景信息（如展开缩写、补充领域术语）
            3. 保持简洁，不超过原问题 1.5 倍长度
            只返回改写后的问题本身，不要解释、不要引号、不要编号。
            """;

    private final LlmClient llmClient;
    private final RagProperties ragProperties;

    /** @return 参与向量检索的查询列表（首元素恒为原查询） */
    public List<String> expand(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        if (!rewriterEnabled() || estimateTokens(query) <= minTokens()) {
            return List.of(query);
        }
        try {
            String rewritten = sanitize(llmClient.chat(List.of(
                    ChatMessage.system(SYSTEM_PROMPT),
                    ChatMessage.user(query))));
            if (rewritten.isBlank() || rewritten.equals(query.strip())) {
                return List.of(query);
            }
            log.debug("查询改写生效：原=[{}] 改写=[{}]", abbreviate(query), abbreviate(rewritten));
            return List.of(query, rewritten);
        } catch (Exception e) {
            log.warn("查询改写失败，降级只用原查询：{}", e.getMessage());
            return List.of(query);
        }
    }

    /** 英文按空白分词计数，中文按 2 字符折 1 token 估算 */
    static int estimateTokens(String query) {
        String compact = query.strip().replaceAll("\\s+", " ");
        if (compact.isEmpty()) {
            return 0;
        }
        int words = compact.split(" ").length;
        long cjkChars = compact.chars().filter(c -> c > 127).count();
        return words + (int) Math.ceil(cjkChars / 2.0);
    }

    private String sanitize(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.strip();
        if (s.startsWith("```")) {
            s = s.replace("```json", "").replace("```", "").strip();
        }
        s = s.replaceAll("\\s+", " ");
        if (s.length() >= 2
                && ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'")))) {
            s = s.substring(1, s.length() - 1).strip();
        }
        return s.length() > MAX_REWRITE_LENGTH ? s.substring(0, MAX_REWRITE_LENGTH) : s;
    }

    private String abbreviate(String text) {
        return text.length() <= 50 ? text : text.substring(0, 50) + "…";
    }

    private boolean rewriterEnabled() {
        return ragProperties.getRetrieval() != null && ragProperties.getRetrieval().isRewriterEnabled();
    }

    private int minTokens() {
        return ragProperties.getRetrieval() == null || ragProperties.getRetrieval().getRewriterMinTokens() <= 0
                ? 15
                : ragProperties.getRetrieval().getRewriterMinTokens();
    }
}

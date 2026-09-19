package com.agenticrag.rag.retrieve;

import com.agenticrag.config.RagProperties;
import com.agenticrag.llm.LlmClient;
import com.agenticrag.llm.dto.ChatMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * HyDE 轻量扩展器（M6 Phase 3.2）：
 * 对原查询生成一段"假设性答案片段"，用该片段做向量检索参与 RRF 融合。
 * - 由 rag.retrieval.hyde-enabled 开关控制：评测时仅对 semantic/basic 题型开启，
 *   constrained 题型关闭（运行时无法感知题型，用评测开关区分）
 * - 任何 LLM 异常 fail-open：返回 empty 跳过该路检索，不影响主链路
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HydeExpander {

    private static final int MAX_PASSAGE_LENGTH = 300;

    private static final String SYSTEM_PROMPT = """
            假设你是企业知识库专员。针对给定的用户问题，生成一段简短的假设性答案片段（80 词以内），
            内容包含可能出现在真实文档中的术语、关键词与细节表述。
            只返回答案片段本身，不要解释、不要引号。
            """;

    private final LlmClient llmClient;
    private final RagProperties ragProperties;

    public Optional<String> hypothesize(String query) {
        if (!hydeEnabled() || query == null || query.isBlank()) {
            return Optional.empty();
        }
        try {
            String passage = sanitize(llmClient.chat(List.of(
                    ChatMessage.system(SYSTEM_PROMPT),
                    ChatMessage.user(query))));
            if (passage.isBlank()) {
                return Optional.empty();
            }
            log.debug("HyDE 假设片段生成（{} 字）：[{}]", passage.length(), abbreviate(passage));
            return Optional.of(passage);
        } catch (Exception e) {
            log.warn("HyDE 生成失败，跳过假设片段检索：{}", e.getMessage());
            return Optional.empty();
        }
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
        return s.length() > MAX_PASSAGE_LENGTH ? s.substring(0, MAX_PASSAGE_LENGTH) : s;
    }

    private String abbreviate(String text) {
        return text.length() <= 50 ? text : text.substring(0, 50) + "…";
    }

    private boolean hydeEnabled() {
        return ragProperties.getRetrieval() != null && ragProperties.getRetrieval().isHydeEnabled();
    }
}

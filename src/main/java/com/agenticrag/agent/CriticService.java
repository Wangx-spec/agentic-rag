package com.agenticrag.agent;

import com.agenticrag.config.RagProperties;
import com.agenticrag.intent.Intent;
import com.agenticrag.llm.LlmClient;
import com.agenticrag.llm.dto.ChatMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * S3.2：最终回答前的轻量证据自检。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CriticService {

    private final LlmClient llmClient;
    private final RagProperties ragProperties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public CriticResult review(String answer, AgentContext ctx) {
        if (!shouldReview(answer, ctx)) {
            return CriticResult.pass();
        }
        if (ctx.getEvidenceRegistry().isEmpty() && containsDigit(answer)) {
            return new CriticResult(CriticVerdict.DECLARE_UNCERTAIN, "回答包含具体数字，但本轮没有可回溯证据。");
        }
        try {
            String response = llmClient.chat(List.of(
                    ChatMessage.system(buildPrompt(ctx.getEvidenceRegistry().snapshot())),
                    ChatMessage.user(answer)
            ));
            return parse(response);
        } catch (Exception e) {
            log.warn("Critic 自检失败，fail-open 放行", e);
            return CriticResult.pass();
        }
    }

    public boolean shouldReview(String answer, AgentContext ctx) {
        if (!criticEnabled() || answer == null || answer.isBlank() || ctx == null || ctx.getToolCallCount() == 0) {
            return false;
        }
        Intent intent = ctx.getRoutedIntent();
        return intent == Intent.KB_QA || intent == Intent.DATA_ANALYSIS || intent == Intent.MULTI_TASK;
    }

    public String uncertainSuffix(String reason) {
        if (reason == null || reason.isBlank()) {
            return "\n\n说明：以上回答中存在未能被本轮证据充分支撑的内容，请以已列出的证据为准。";
        }
        return "\n\n说明：" + reason + " 请以已列出的证据为准。";
    }

    private String buildPrompt(List<EvidenceRegistry.Evidence> evidence) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是证据自检器。请判断待检查回答中的关键数字、排名、对比结论是否能被证据支持。")
                .append("只返回 JSON：{\"verdict\":\"PASS|RETRY|DECLARE_UNCERTAIN\",\"reason\":\"...\"}。\n\n证据：\n");
        for (EvidenceRegistry.Evidence item : evidence) {
            sb.append("[").append(item.id()).append("] ")
                    .append(item.type()).append(" ")
                    .append(item.summary()).append("\n");
        }
        return sb.toString();
    }

    private CriticResult parse(String response) {
        try {
            String json = stripCodeFence(response);
            JsonNode node = objectMapper.readTree(json);
            CriticVerdict verdict = CriticVerdict.valueOf(node.path("verdict").asText("PASS").trim().toUpperCase());
            String reason = node.path("reason").asText("");
            return new CriticResult(verdict, reason);
        } catch (Exception e) {
            log.warn("Critic 响应解析失败，fail-open 放行: {}", response);
            return CriticResult.pass();
        }
    }

    private boolean criticEnabled() {
        return ragProperties.getAgent() == null
                || ragProperties.getAgent().getCritic() == null
                || ragProperties.getAgent().getCritic().isEnabled();
    }

    private boolean containsDigit(String text) {
        return text != null && text.matches(".*\\d.*");
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

    public enum CriticVerdict {
        PASS,
        RETRY,
        DECLARE_UNCERTAIN
    }

    public record CriticResult(CriticVerdict verdict, String reason) {
        public static CriticResult pass() {
            return new CriticResult(CriticVerdict.PASS, "");
        }
    }
}

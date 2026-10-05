package com.agenticrag.eval.da;

import com.agenticrag.llm.LlmClient;
import com.agenticrag.llm.dto.ChatMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
public class DaJudge {

    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;

    public DaJudge(LlmClient llmClient, ObjectMapper objectMapper) {
        this.llmClient = llmClient;
        this.objectMapper = objectMapper;
    }

    public JudgeResult judge(DaEvalQuestion question, DaEvalAnswer answer) {
        if (question.expectedPoints().isEmpty()) {
            return new JudgeResult(question.questionId(), 1.0, List.of());
        }
        try {
            return parse(question.questionId(), llmClient.chat(List.of(
                    ChatMessage.system(prompt()),
                    ChatMessage.user(buildInput(question, answer))
            )));
        } catch (Exception first) {
            log.warn("数据分析 LLM judge 首次判分失败，重试一次: questionId={}", question.questionId(), first);
            try {
                return parse(question.questionId(), llmClient.chat(List.of(
                        ChatMessage.system(prompt()),
                        ChatMessage.user(buildInput(question, answer))
                )));
            } catch (Exception second) {
                log.warn("数据分析 LLM judge 重试失败，按 0 分处理: questionId={}", question.questionId(), second);
                return new JudgeResult(question.questionId(), 0.0,
                        List.of(new PointResult("judge_error", false, second.getMessage())));
            }
        }
    }

    private String prompt() {
        return """
                你是数据分析评测判分器。请根据 question、expected_points 和 model_answer 判断每个要点是否命中。
                只返回 JSON，格式：
                {"points":[{"point":"...","hit":true,"reason":"..."}]}
                判断应关注语义等价，不要求逐字相同；不要补充 Markdown。
                """;
    }

    private String buildInput(DaEvalQuestion question, DaEvalAnswer answer) throws Exception {
        return objectMapper.writeValueAsString(java.util.Map.of(
                "question", question.question(),
                "expected_points", question.expectedPoints(),
                "model_answer", answer == null ? "" : answer.answer()
        ));
    }

    private JudgeResult parse(String questionId, String response) throws Exception {
        JsonNode root = objectMapper.readTree(stripFence(response));
        List<PointResult> points = new ArrayList<>();
        JsonNode pointNodes = root.path("points");
        if (pointNodes.isArray()) {
            for (JsonNode node : pointNodes) {
                points.add(new PointResult(
                        node.path("point").asText(""),
                        node.path("hit").asBoolean(false),
                        node.path("reason").asText("")
                ));
            }
        }
        long hit = points.stream().filter(PointResult::hit).count();
        double score = points.isEmpty() ? 0.0 : (double) hit / points.size();
        return new JudgeResult(questionId, score, points);
    }

    private String stripFence(String response) {
        if (response == null) {
            return "{}";
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

    public record JudgeResult(String questionId, double score, List<PointResult> points) {
        public JudgeResult {
            points = points == null ? List.of() : List.copyOf(points);
        }
    }

    public record PointResult(String point, boolean hit, String reason) {
    }
}

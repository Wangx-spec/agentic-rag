package com.agenticrag.eval.da;

import com.agenticrag.eval.trace.TraceEvent;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class MetricsDeriver {

    public Report derive(List<DaEvalQuestion> questions,
                         List<TraceEvent> traces,
                         List<RuleChecker.RuleResult> ruleResults,
                         List<DaJudge.JudgeResult> judgeResults) {
        int total = questions == null ? 0 : questions.size();
        Map<String, List<TraceEvent>> byQuestion = groupByQuestion(traces);
        double sqlCorrect = average(ruleResults, RuleChecker.RuleResult::sqlPassed);
        double evidenceTraceable = average(ruleResults, RuleChecker.RuleResult::evidencePassed);
        double refusal = average(ruleResults, RuleChecker.RuleResult::refusalPassed);
        double answerAccuracy = judgeResults == null || judgeResults.isEmpty()
                ? 0.0
                : judgeResults.stream().mapToDouble(DaJudge.JudgeResult::score).average().orElse(0.0);
        double routeAccuracy = routeAccuracy(byQuestion.values());
        double criticBlockRate = criticBlockRate(traces);
        double avgTurns = byQuestion.values().stream()
                .mapToInt(events -> events.stream().mapToInt(TraceEvent::turn).max().orElse(0) + 1)
                .average().orElse(0.0);
        double avgTokens = traces == null || traces.isEmpty()
                ? 0.0
                : traces.stream().mapToInt(event -> event.tokensUsed() == null ? 0 : event.tokensUsed()).average().orElse(0.0);
        return new Report(total, sqlCorrect, answerAccuracy, routeAccuracy, criticBlockRate,
                evidenceTraceable, refusal, avgTurns, avgTokens);
    }

    public String renderMarkdown(String runId,
                                 List<DaEvalQuestion> questions,
                                 List<RuleChecker.RuleResult> ruleResults,
                                 List<DaJudge.JudgeResult> judgeResults,
                                 Report report) {
        Map<String, RuleChecker.RuleResult> rules = index(ruleResults);
        Map<String, DaJudge.JudgeResult> judges = judgeResults == null ? Map.of() : judgeResults.stream()
                .collect(Collectors.toMap(DaJudge.JudgeResult::questionId, item -> item, (a, b) -> a));
        StringBuilder sb = new StringBuilder();
        sb.append("# Data Analysis Eval Report\n\n");
        sb.append("- run_id: `").append(runId).append("`\n");
        sb.append("- token 口径: `chars/1.5` 估算（当前 LlmResponse 未暴露 usage）\n\n");
        sb.append("## Summary\n");
        sb.append("| 指标 | 数值 |\n|---|---:|\n");
        sb.append("| SQL 正确性 | ").append(percent(report.sqlCorrectRate())).append(" |\n");
        sb.append("| 答案准确率 | ").append(percent(report.answerAccuracy())).append(" |\n");
        sb.append("| 路由准确率 | ").append(percent(report.routeAccuracy())).append(" |\n");
        sb.append("| Critic 拦截率 | ").append(percent(report.criticBlockRate())).append(" |\n");
        sb.append("| 证据可回溯率 | ").append(percent(report.evidenceTraceableRate())).append(" |\n");
        sb.append("| 拒绝题通过率 | ").append(percent(report.refusalPassRate())).append(" |\n");
        sb.append("| 平均轮次 | ").append(number(report.avgTurns())).append(" |\n");
        sb.append("| 平均 token(估算) | ").append(number(report.avgTokens())).append(" |\n\n");

        sb.append("## Cases\n");
        sb.append("| id | type | rule | judge | detail |\n|---|---|---:|---:|---|\n");
        for (DaEvalQuestion question : questions == null ? List.<DaEvalQuestion>of() : questions) {
            RuleChecker.RuleResult rule = rules.get(question.questionId());
            DaJudge.JudgeResult judge = judges.get(question.questionId());
            sb.append("| ").append(question.questionId())
                    .append(" | ").append(question.type())
                    .append(" | ").append(rule != null && rule.passed() ? "Y" : "N")
                    .append(" | ").append(judge == null ? "-" : number(judge.score()))
                    .append(" | ").append(rule == null ? "" : escape(rule.detail()))
                    .append(" |\n");
        }
        return sb.toString();
    }

    public Map<String, List<TraceEvent>> groupByQuestion(List<TraceEvent> traces) {
        return (traces == null ? List.<TraceEvent>of() : traces).stream()
                .collect(Collectors.groupingBy(TraceEvent::questionId));
    }

    private double routeAccuracy(Collection<List<TraceEvent>> tracesByQuestion) {
        if (tracesByQuestion == null || tracesByQuestion.isEmpty()) {
            return 0.0;
        }
        long passed = tracesByQuestion.stream().filter(this::routeMatchesTools).count();
        return (double) passed / tracesByQuestion.size();
    }

    private boolean routeMatchesTools(List<TraceEvent> events) {
        String intent = events.stream()
                .filter(event -> "intent".equals(event.eventType()))
                .map(TraceEvent::routedIntent)
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .orElse("UNKNOWN");
        Set<String> allowed = allowedDomains(intent);
        Set<String> actual = events.stream()
                .filter(event -> "tool_call".equals(event.eventType()))
                .map(TraceEvent::toolName)
                .filter(name -> name != null && !name.isBlank())
                .map(this::domainOfTool)
                .filter(domain -> !"unknown".equals(domain))
                .collect(Collectors.toSet());
        return allowed.containsAll(actual);
    }

    private Set<String> allowedDomains(String intent) {
        return switch (intent == null ? "UNKNOWN" : intent.toUpperCase(Locale.ROOT)) {
            case "KB_QA" -> Set.of("retrieval", "document", "memory");
            case "DATA_ANALYSIS" -> Set.of("sql", "calc");
            case "TOOL_TASK" -> Set.of("calc", "retrieval", "memory");
            case "MULTI_TASK", "UNKNOWN" -> Set.of("retrieval", "document", "memory", "sql", "calc", "mcp");
            case "CHAT", "OFF_TOPIC" -> Set.of();
            default -> Set.of("retrieval", "document", "memory", "sql", "calc", "mcp");
        };
    }

    private String domainOfTool(String toolName) {
        if ("run_sql".equals(toolName) || "list_tables".equals(toolName)) {
            return "sql";
        }
        if ("search_knowledge_base".equals(toolName)) {
            return "retrieval";
        }
        if ("get_document".equals(toolName) || "list_documents".equals(toolName)) {
            return "document";
        }
        if ("search_memory".equals(toolName)) {
            return "memory";
        }
        if ("calculator".equals(toolName)) {
            return "calc";
        }
        return "unknown";
    }

    private double criticBlockRate(List<TraceEvent> traces) {
        List<TraceEvent> critic = (traces == null ? List.<TraceEvent>of() : traces).stream()
                .filter(event -> "critic".equals(event.eventType()))
                .toList();
        if (critic.isEmpty()) {
            return 0.0;
        }
        long blocked = critic.stream()
                .filter(event -> event.criticVerdict() != null && !"PASS".equalsIgnoreCase(event.criticVerdict()))
                .count();
        return (double) blocked / critic.size();
    }

    private Map<String, RuleChecker.RuleResult> index(List<RuleChecker.RuleResult> ruleResults) {
        Map<String, RuleChecker.RuleResult> result = new HashMap<>();
        for (RuleChecker.RuleResult item : ruleResults == null ? List.<RuleChecker.RuleResult>of() : ruleResults) {
            result.put(item.questionId(), item);
        }
        return result;
    }

    private double average(List<RuleChecker.RuleResult> results, java.util.function.Predicate<RuleChecker.RuleResult> predicate) {
        if (results == null || results.isEmpty()) {
            return 0.0;
        }
        long passed = results.stream().filter(predicate).count();
        return (double) passed / results.size();
    }

    private String percent(double value) {
        return String.format(Locale.ROOT, "%.1f%%", value * 100);
    }

    private String number(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private String escape(String value) {
        return value == null ? "" : value.replace("|", "\\|").replace("\n", " ");
    }

    public record Report(
            int total,
            double sqlCorrectRate,
            double answerAccuracy,
            double routeAccuracy,
            double criticBlockRate,
            double evidenceTraceableRate,
            double refusalPassRate,
            double avgTurns,
            double avgTokens
    ) {
    }
}

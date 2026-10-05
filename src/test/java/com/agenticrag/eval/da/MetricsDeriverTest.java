package com.agenticrag.eval.da;

import com.agenticrag.eval.trace.TraceEvent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetricsDeriverTest {

    private final MetricsDeriver deriver = new MetricsDeriver();

    @Test
    void derivesRouteCriticAndEfficiencyMetrics() {
        List<DaEvalQuestion> questions = List.of(
                question("q1", "single_sql"),
                question("q2", "hybrid_sql_text")
        );
        List<TraceEvent> traces = List.of(
                event("q1", 0, "intent", null, null, "DATA_ANALYSIS", null, 4),
                event("q1", 0, "tool_call", "run_sql", null, null, null, 6),
                event("q1", 1, "critic", null, null, null, "PASS", 3),
                event("q2", 0, "intent", null, null, "MULTI_TASK", null, 4),
                event("q2", 0, "tool_call", "run_sql", null, null, null, 6),
                event("q2", 1, "tool_call", "search_knowledge_base", null, null, null, 6),
                event("q2", 2, "critic", null, null, null, "RETRY", 3)
        );
        List<RuleChecker.RuleResult> rules = List.of(
                new RuleChecker.RuleResult("q1", true, true, true, true, "ok"),
                new RuleChecker.RuleResult("q2", false, true, false, true, "missing document")
        );
        List<DaJudge.JudgeResult> judges = List.of(
                new DaJudge.JudgeResult("q1", 1.0, List.of()),
                new DaJudge.JudgeResult("q2", 0.5, List.of())
        );

        MetricsDeriver.Report report = deriver.derive(questions, traces, rules, judges);

        assertEquals(2, report.total());
        assertEquals(1.0, report.sqlCorrectRate(), 0.0001);
        assertEquals(0.75, report.answerAccuracy(), 0.0001);
        assertEquals(1.0, report.routeAccuracy(), 0.0001);
        assertEquals(0.5, report.criticBlockRate(), 0.0001);
        assertEquals(0.5, report.evidenceTraceableRate(), 0.0001);
        assertTrue(report.avgTurns() > 0);
        assertTrue(report.avgTokens() > 0);
    }

    @Test
    void rendersMarkdownWithSummaryAndCases() {
        String markdown = deriver.renderMarkdown("da-r1",
                List.of(question("q1", "single_sql")),
                List.of(new RuleChecker.RuleResult("q1", true, true, true, true, "ok")),
                List.of(new DaJudge.JudgeResult("q1", 1.0, List.of())),
                new MetricsDeriver.Report(1, 1, 1, 1, 0, 1, 1, 2, 10));

        assertTrue(markdown.contains("## Summary"));
        assertTrue(markdown.contains("SQL 正确性"));
        assertTrue(markdown.contains("| q1 |"));
    }

    private DaEvalQuestion question(String id, String type) {
        return new DaEvalQuestion(id, "问题", type, "", List.of("要点"), List.of("sql"), "answer");
    }

    private TraceEvent event(String questionId, int turn, String type, String toolName,
                             String result, String intent, String critic, int tokens) {
        return new TraceEvent("r", questionId, turn, type, toolName, null, result,
                List.of(), List.of(), intent, critic, tokens);
    }
}

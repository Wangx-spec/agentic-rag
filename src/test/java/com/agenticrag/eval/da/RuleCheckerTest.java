package com.agenticrag.eval.da;

import com.agenticrag.eval.trace.TraceEvent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleCheckerTest {

    private final RuleChecker checker = new RuleChecker();

    @Test
    void sqlPatternPassesWhenRunSqlArgsMatch() {
        DaEvalQuestion question = question("da-001", "(?i)group\\s+by.*category", List.of("sql"), "answer");
        DaEvalAnswer answer = new DaEvalAnswer("da-001", "前三名是 A、B、C [1]。", List.of("1"));

        RuleChecker.RuleResult result = checker.check(question, answer, List.of(
                tool("run_sql", "{\"sql\":\"select category, sum(item_total) from order_items group by category\"}"),
                evidence("1", "sql")
        ));

        assertTrue(result.sqlPassed());
        assertTrue(result.evidencePassed());
        assertTrue(result.passed());
    }

    @Test
    void evidenceTypesUseSupersetSemantics() {
        DaEvalQuestion question = question("da-002", "", List.of("sql", "document"), "answer");

        RuleChecker.RuleResult pass = checker.check(question,
                new DaEvalAnswer("da-002", "结论 [1][2][3]。", List.of("1", "2", "3")),
                List.of(evidence("1", "sql"), evidence("2", "document"), evidence("3", "memory")));
        RuleChecker.RuleResult fail = checker.check(question,
                new DaEvalAnswer("da-002", "结论 [1]。", List.of("1")),
                List.of(evidence("1", "sql")));

        assertTrue(pass.evidencePassed());
        assertFalse(fail.evidencePassed());
    }

    @Test
    void refusalPassesWhenAnswerRefusesAndNoWriteExecuted() {
        DaEvalQuestion question = question("da-003", "", List.of(), "refuse");

        RuleChecker.RuleResult result = checker.check(question,
                new DaEvalAnswer("da-003", "我不能执行删除订单表这样的写操作。", List.of()),
                List.of());

        assertTrue(result.refusalPassed());
        assertTrue(result.passed());
    }

    @Test
    void refusalFailsWhenWriteSqlWasExecuted() {
        DaEvalQuestion question = question("da-004", "", List.of(), "refuse");

        RuleChecker.RuleResult result = checker.check(question,
                new DaEvalAnswer("da-004", "已处理。", List.of()),
                List.of(tool("run_sql", "{\"sql\":\"delete from orders\"}")));

        assertFalse(result.refusalPassed());
    }

    private DaEvalQuestion question(String id, String sqlPattern, List<String> evidenceTypes, String behavior) {
        return new DaEvalQuestion(id, "问题", "single_sql", sqlPattern, List.of("要点"), evidenceTypes, behavior);
    }

    private TraceEvent tool(String name, String args) {
        return new TraceEvent("r", "q", 0, "tool_call", name, args, "", List.of(), List.of(), null, null, 1);
    }

    private TraceEvent evidence(String id, String type) {
        return new TraceEvent("r", "q", 0, "evidence", null, null, "", List.of(id), List.of(type), null, null, 1);
    }
}

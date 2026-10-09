package com.agenticrag.agent;

import com.agenticrag.config.RagProperties;
import com.agenticrag.llm.dto.ChatMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BudgetGuardTest {

    @Test
    void truncatesLongSqlResult() {
        RagProperties props = new RagProperties();
        props.getAgent().getBudget().setSqlMaxChars(10);
        BudgetGuard guard = new BudgetGuard(props);

        String result = guard.truncate("run_sql", "0123456789abcdef");

        assertTrue(result.contains("已截断"));
        assertTrue(result.startsWith("0123456789"));
    }

    @Test
    void singleLongTextKeepsOriginalHeadTruncation() {
        RagProperties props = new RagProperties();
        props.getAgent().getBudget().setSqlMaxChars(10);
        BudgetGuard guard = new BudgetGuard(props);

        String result = guard.truncate("run_sql", "0123456789abcdef");

        assertTrue(result.startsWith("0123456789"));
        assertFalse(result.contains("公平截断"));
    }

    @Test
    void fairlyTruncatesMultiRecordResultsWithoutDroppingRecords() {
        RagProperties props = new RagProperties();
        props.getAgent().getBudget().setSqlMaxChars(30);
        BudgetGuard guard = new BudgetGuard(props);

        String result = guard.truncate("run_sql", "row1-abcdef\nrow2-abcdef\nrow3-abcdef\nrow4-abcdef");

        assertTrue(result.contains("row1-"));
        assertTrue(result.contains("row2-"));
        assertTrue(result.contains("row3-"));
        assertTrue(result.contains("row4-"));
        assertTrue(result.contains("公平截断"));
    }

    @Test
    void disabledBudgetDoesNotTruncate() {
        RagProperties props = new RagProperties();
        props.getAgent().getBudget().setEnabled(false);
        props.getAgent().getBudget().setSqlMaxChars(10);
        BudgetGuard guard = new BudgetGuard(props);

        String result = guard.truncate("run_sql", "0123456789abcdef");

        assertFalse(result.contains("已截断"));
    }

    @Test
    void detectsToolCallLimit() {
        RagProperties props = new RagProperties();
        props.getAgent().getBudget().setMaxToolCallsPerSession(1);
        props.getAgent().getBudget().setMaxTotalTokens(1000);
        BudgetGuard guard = new BudgetGuard(props);
        AgentContext ctx = new AgentContext(List.of(ChatMessage.user("问题")), List.of(), 5);
        ctx.incrementToolCallCount();

        assertTrue(guard.shouldFinalize(ctx));
    }
}

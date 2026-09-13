package com.agenticrag.multiagent;

import com.agenticrag.llm.LlmClient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LeaderAgentTest {

    private final LlmClient llmClient = mock(LlmClient.class);
    private final LeaderAgent leaderAgent = new LeaderAgent(llmClient);

    @Test
    void parsePlanResponseReturnsSubQuestionsFromJson() {
        List<String> result = leaderAgent.parsePlanResponse("""
                {"subQuestions":["A 是什么？","A 和 B 有什么区别？"]}
                """);

        assertEquals(List.of("A 是什么？", "A 和 B 有什么区别？"), result);
    }

    @Test
    void parsePlanResponseReturnsEmptyListWhenJsonMalformed() {
        List<String> result = leaderAgent.parsePlanResponse("{bad json");

        assertTrue(result.isEmpty());
    }

    @Test
    void planTruncatesToFourSubQuestions() {
        when(llmClient.chat(anyList())).thenReturn("""
                {"subQuestions":["q1","q2","q3","q4","q5"]}
                """);

        List<String> result = leaderAgent.plan("复杂问题");

        assertEquals(List.of("q1", "q2", "q3", "q4"), result);
    }
}

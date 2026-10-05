package com.agenticrag.agent;

import com.agenticrag.config.RagProperties;
import com.agenticrag.intent.Intent;
import com.agenticrag.llm.LlmClient;
import com.agenticrag.llm.dto.ChatMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CriticServiceTest {

    @Test
    void parsesPassVerdictFromLlm() {
        LlmClient llmClient = mock(LlmClient.class);
        when(llmClient.chat(anyList())).thenReturn("{\"verdict\":\"PASS\",\"reason\":\"ok\"}");
        CriticService service = new CriticService(llmClient, new RagProperties());
        AgentContext ctx = context(Intent.DATA_ANALYSIS);
        ctx.incrementToolCallCount();
        ctx.getEvidenceRegistry().registerSqlResult("SELECT 1", List.of("x"), List.of(List.of(1)), false);

        CriticService.CriticResult result = service.review("结果是 1 [1]", ctx);

        assertEquals(CriticService.CriticVerdict.PASS, result.verdict());
    }

    @Test
    void skipsChatIntentWithoutCallingLlm() {
        LlmClient llmClient = mock(LlmClient.class);
        CriticService service = new CriticService(llmClient, new RagProperties());
        AgentContext ctx = context(Intent.CHAT);
        ctx.incrementToolCallCount();

        CriticService.CriticResult result = service.review("一年有 12 个月。", ctx);

        assertEquals(CriticService.CriticVerdict.PASS, result.verdict());
        verifyNoInteractions(llmClient);
    }

    @Test
    void declaresUncertainWhenEvidenceExpectedButMissing() {
        CriticService service = new CriticService(mock(LlmClient.class), new RagProperties());
        AgentContext ctx = context(Intent.DATA_ANALYSIS);
        ctx.incrementToolCallCount();

        CriticService.CriticResult result = service.review("销售额是 999。", ctx);

        assertEquals(CriticService.CriticVerdict.DECLARE_UNCERTAIN, result.verdict());
    }

    private static AgentContext context(Intent intent) {
        AgentContext ctx = new AgentContext(List.of(ChatMessage.user("问题")), List.of(), 5);
        ctx.setRoutedIntent(intent);
        return ctx;
    }
}

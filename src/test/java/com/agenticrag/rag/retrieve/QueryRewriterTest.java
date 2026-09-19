package com.agenticrag.rag.retrieve;

import com.agenticrag.config.RagProperties;
import com.agenticrag.llm.LlmClient;
import com.agenticrag.llm.dto.ChatMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static com.agenticrag.rag.retrieve.QueryRewriter.estimateTokens;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

/**
 * M6 Phase 3.1 单测：Query 改写器（Mockito mock LlmClient + RagProperties）。
 * 核心验证路径：开关关闭/短查询不触发/正常改写/LLM 异常 fail-open/空白响应降级。
 */
@ExtendWith(MockitoExtension.class)
class QueryRewriterTest {

    @Mock
    private LlmClient llmClient;

    private RagProperties ragProperties;
    private QueryRewriter queryRewriter;

    @BeforeEach
    void setUp() {
        ragProperties = new RagProperties();
        queryRewriter = new QueryRewriter(llmClient, ragProperties);
    }

    // ==================== 开关与阈值 ====================

    @Test
    void rewriterDisabledReturnsOnlyOriginal() {
        ragProperties.getRetrieval().setRewriterEnabled(false);
        List<String> result = queryRewriter.expand(
                "What is the validity period for the telemetry driven runbook author certification process?");
        assertEquals(List.of("What is the validity period for the telemetry driven runbook author certification process?"),
                result);
    }

    @Test
    void shortQueryNotRewritten() {
        ragProperties.getRetrieval().setRewriterEnabled(true);
        // "short query" → ~2 words + 0 cjk = 2 tokens < 15 threshold
        List<String> result = queryRewriter.expand("short query");
        assertEquals(List.of("short query"), result);
    }

    @Test
    void nullQueryReturnsEmpty() {
        ragProperties.getRetrieval().setRewriterEnabled(true);
        assertTrue(queryRewriter.expand(null).isEmpty());
    }

    @Test
    void blankQueryReturnsEmpty() {
        ragProperties.getRetrieval().setRewriterEnabled(true);
        assertTrue(queryRewriter.expand("  ").isEmpty());
    }

    // ==================== 正常改写路径 ====================

    // 改写触发阈值内所有测试共用此查询（21 词 > 15 tokens）
    private static final String LONG_QUERY =
            "What is the validity period for the telemetry driven runbook author certification process and how to renew it?";

    @Test
    void longQueryTriggersRewrite() {
        ragProperties.getRetrieval().setRewriterEnabled(true);
        String rewritten = "How long does the certification for telemetry runbook authoring remain valid?";
        when(llmClient.chat(anyList())).thenReturn(rewritten);

        List<String> result = queryRewriter.expand(LONG_QUERY);
        assertEquals(2, result.size());
        assertEquals(LONG_QUERY, result.get(0));
        assertEquals(rewritten, result.get(1));
    }

    @Test
    void rewriteIdenticalToOriginalReturnsOnlyOriginal() {
        ragProperties.getRetrieval().setRewriterEnabled(true);
        when(llmClient.chat(anyList())).thenReturn(LONG_QUERY);

        List<String> result = queryRewriter.expand(LONG_QUERY);
        assertEquals(List.of(LONG_QUERY), result);
    }

    // ==================== fail-open 降级 ====================

    @Test
    void llmExceptionFailOpenReturnsOriginal() {
        ragProperties.getRetrieval().setRewriterEnabled(true);
        when(llmClient.chat(anyList())).thenThrow(new RuntimeException("LLM timeout"));

        List<String> result = queryRewriter.expand(LONG_QUERY);
        assertEquals(List.of(LONG_QUERY), result);
    }

    @Test
    void llmReturnsBlankFailOpenReturnsOriginal() {
        ragProperties.getRetrieval().setRewriterEnabled(true);
        when(llmClient.chat(anyList())).thenReturn("  ");

        List<String> result = queryRewriter.expand(LONG_QUERY);
        assertEquals(List.of(LONG_QUERY), result);
    }

    // ==================== estimateTokens ====================

    @Test
    void estimatePureEnglish() {
        // 5 words
        assertEquals(5, estimateTokens("the quick brown fox jumps"));
    }

    @Test
    void estimatePureChinese() {
        // 6 CJK chars 作为一个词（无空格分开）→ 1 + ceil(6/2) = 4
        assertEquals(4, estimateTokens("你好世界检索"));
    }

    @Test
    void estimateMixed() {
        // "hello world 你好" → 3 words + 2 CJK chars → 3 + ceil(2/2) = 4
        assertEquals(4, estimateTokens("hello world 你好"));
    }
}
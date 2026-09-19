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
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

/**
 * M6 Phase 3.2 单测：HyDE 假设性答案扩展器（Mockito mock LlmClient + RagProperties）。
 * 核心验证路径：开关关闭/正常生成/LLM 异常 fail-open/空白响应返回 empty。
 */
@ExtendWith(MockitoExtension.class)
class HydeExpanderTest {

    @Mock
    private LlmClient llmClient;

    private RagProperties ragProperties;
    private HydeExpander hydeExpander;

    @BeforeEach
    void setUp() {
        ragProperties = new RagProperties();
        hydeExpander = new HydeExpander(llmClient, ragProperties);
    }

    // ==================== 开关控制 ====================

    @Test
    void hydeDisabledReturnsEmpty() {
        ragProperties.getRetrieval().setHydeEnabled(false);
        assertEquals(Optional.empty(), hydeExpander.hypothesize("What is RAG?"));
    }

    @Test
    void nullQueryReturnsEmpty() {
        ragProperties.getRetrieval().setHydeEnabled(true);
        assertEquals(Optional.empty(), hydeExpander.hypothesize(null));
    }

    @Test
    void blankQueryReturnsEmpty() {
        ragProperties.getRetrieval().setHydeEnabled(true);
        assertEquals(Optional.empty(), hydeExpander.hypothesize("  "));
    }

    // ==================== 正常路径 ====================

    @Test
    void hydeEnabledGeneratesPassage() {
        ragProperties.getRetrieval().setHydeEnabled(true);
        String query = "What is the validity period for the telemetry driven runbook author certification?";
        String passage = "The certification for telemetry runbook authors is valid for 12 months from issuance date.";
        when(llmClient.chat(anyList())).thenReturn(passage);

        Optional<String> result = hydeExpander.hypothesize(query);
        assertTrue(result.isPresent());
        assertEquals(passage, result.get());
    }

    // ==================== fail-open 降级 ====================

    @Test
    void llmExceptionFailOpenReturnsEmpty() {
        ragProperties.getRetrieval().setHydeEnabled(true);
        when(llmClient.chat(anyList())).thenThrow(new RuntimeException("LLM timeout"));

        assertEquals(Optional.empty(), hydeExpander.hypothesize("What is RAG?"));
    }

    @Test
    void llmReturnsBlankFailOpenReturnsEmpty() {
        ragProperties.getRetrieval().setHydeEnabled(true);
        when(llmClient.chat(anyList())).thenReturn("   ");

        assertEquals(Optional.empty(), hydeExpander.hypothesize("What is RAG?"));
    }
}
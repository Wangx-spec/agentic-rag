package com.agenticrag.eval;

import com.agenticrag.intent.Intent;
import com.agenticrag.intent.IntentClassifier;
import com.agenticrag.rag.retrieve.RetrievedChunk;
import com.agenticrag.service.ChatMode;
import com.agenticrag.service.ChatResult;
import com.agenticrag.service.ToolInvocation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EvalComponentsTest {

    private final AnswerAccuracyEval answerAccuracyEval = new AnswerAccuracyEval();
    private final RecallEval recallEval = new RecallEval();
    private final CitationEval citationEval = new CitationEval();
    private final ToolSuccessEval toolSuccessEval = new ToolSuccessEval();

    @Test
    void answerAccuracyComputesTokenOverlapF1() {
        var result = answerAccuracyEval.evaluate(
                "系统使用向量检索和 BM25。",
                "系统使用向量检索和 BM25 关键词检索。"
        );

        assertTrue(result.f1() > 0.5);
        assertTrue(result.passed(0.5));
    }

    @Test
    void recallAndCitationUseReturnedSources() {
        List<RetrievedChunk> sources = List.of(
                new RetrievedChunk(3L, 1L, 1, "片段一", "doc.md", 1.0, 1),
                new RetrievedChunk(5L, 1L, 2, "片段二", "doc.md", 0.8, 2)
        );

        var recall = recallEval.evaluate(sources, List.of(5L));
        var citation = citationEval.evaluate("答案 [1][2]", sources);

        assertEquals(1.0, recall.recallAt10());
        assertEquals(1.0, citation.validRate());
    }

    @Test
    void toolSuccessChecksExpectedToolInvocation() {
        ChatResult result = new ChatResult(
                "112",
                List.of(),
                ChatMode.AGENT,
                Intent.TOOL_TASK,
                List.of(new ToolInvocation("calculator", "{\"expression\":\"12*9+4\"}")),
                false
        );

        var tool = toolSuccessEval.evaluate(result, "calculator");
        assertTrue(tool.invoked());
        assertTrue(tool.passed());
    }

    @Test
    void intentRoutingAggregatesAccuracy() {
        IntentClassifier classifier = mock(IntentClassifier.class);
        when(classifier.classify("你好")).thenReturn(new IntentClassifier.IntentResult(Intent.CHAT, 0.9));
        when(classifier.classify("请计算 2+2")).thenReturn(new IntentClassifier.IntentResult(Intent.TOOL_TASK, 0.95));

        IntentRoutingEval eval = new IntentRoutingEval(classifier);
        var report = eval.evaluate(List.of(
                new IntentEvalCase("1", "你好", Intent.CHAT),
                new IntentEvalCase("2", "请计算 2+2", Intent.TOOL_TASK)
        ));

        assertEquals(1.0, report.accuracy());
    }
}

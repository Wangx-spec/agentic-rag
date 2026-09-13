package com.agenticrag.intent;

import com.agenticrag.config.LlmProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class IntentClassifierTest {

    private IntentProperties intentProperties;
    private LlmProperties llmProperties;
    private TestableIntentClassifier classifier;

    @BeforeEach
    void setUp() {
        intentProperties = new IntentProperties();
        intentProperties.setEnabled(true);
        intentProperties.setConfidenceThreshold(0.6);

        llmProperties = new LlmProperties();
        llmProperties.setBaseUrl("https://api.example.com/v1");
        llmProperties.setChatModel("test-model");
        llmProperties.setApiKey("test-key");

        classifier = new TestableIntentClassifier(intentProperties, llmProperties);
    }

    @Test
    void classifyReturnsChatForGreeting() {
        classifier.nextResponse = "{\"intent\": \"CHAT\", \"confidence\": 0.95}";
        IntentClassifier.IntentResult result = classifier.classify("你好");
        assertEquals(Intent.CHAT, result.intent());
        assertEquals(0.95, result.confidence(), 0.01);
    }

    @Test
    void classifyReturnsKbQaForDocumentQuestion() {
        classifier.nextResponse = "{\"intent\": \"KB_QA\", \"confidence\": 0.9}";
        IntentClassifier.IntentResult result = classifier.classify("文档里提到的 RAG 是什么意思？");
        assertEquals(Intent.KB_QA, result.intent());
    }

    @Test
    void classifyReturnsToolTaskForCalculation() {
        classifier.nextResponse = "{\"intent\": \"TOOL_TASK\", \"confidence\": 0.92}";
        IntentClassifier.IntentResult result = classifier.classify("计算 123 * 456");
        assertEquals(Intent.TOOL_TASK, result.intent());
    }

    @Test
    void classifyReturnsMultiTaskForCompoundQuestion() {
        classifier.nextResponse = "{\"intent\": \"MULTI_TASK\", \"confidence\": 0.93}";
        IntentClassifier.IntentResult result = classifier.classify("A 是什么？A 和 B 有什么区别？");
        assertEquals(Intent.MULTI_TASK, result.intent());
    }

    @Test
    void classifyReturnsUnknownWhenConfidenceBelowThreshold() {
        classifier.nextResponse = "{\"intent\": \"CHAT\", \"confidence\": 0.4}";
        IntentClassifier.IntentResult result = classifier.classify("你好");
        assertEquals(Intent.UNKNOWN, result.intent());
    }

    @Test
    void classifyReturnsUnknownWhenJsonMalformed() {
        classifier.nextResponse = "{bad json";
        IntentClassifier.IntentResult result = classifier.classify("bad json");
        assertEquals(Intent.UNKNOWN, result.intent());
    }

    @Test
    void classifyReturnsUnknownWhenDisabled() {
        intentProperties.setEnabled(false);
        IntentClassifier.IntentResult result = classifier.classify("Hello");
        assertEquals(Intent.UNKNOWN, result.intent());
    }

    @Test
    void classifyHandlesMarkdownCodeBlock() {
        classifier.nextResponse = "```json\n{\"intent\": \"KB_QA\", \"confidence\": 0.85}\n```";
        IntentClassifier.IntentResult result = classifier.classify("什么是 RAG？");
        assertEquals(Intent.KB_QA, result.intent());
    }

    @Test
    void classifyReturnsUnknownWhenLlmThrows() {
        classifier.throwOnCall = true;
        IntentClassifier.IntentResult result = classifier.classify("什么是 RAG？");
        assertEquals(Intent.UNKNOWN, result.intent());
    }

    @Test
    void classifyUsesDedicatedIntentConfigWhenProvided() {
        intentProperties.setBaseUrl("https://intent.example.com/v1");
        intentProperties.setModel("intent-model");
        intentProperties.setApiKey("intent-key");
        classifier.nextResponse = "{\"intent\": \"CHAT\", \"confidence\": 0.95}";

        classifier.classify("你好");

        assertNotNull(classifier.lastLlmProperties);
        assertEquals("https://intent.example.com/v1", classifier.lastLlmProperties.getBaseUrl());
        assertEquals("intent-model", classifier.lastLlmProperties.getChatModel());
        assertEquals("intent-key", classifier.lastLlmProperties.getApiKey());
    }

    @Test
    void classifyFallsBackToMainLlmConfigWhenIntentConfigBlank() {
        classifier.nextResponse = "{\"intent\": \"CHAT\", \"confidence\": 0.95}";

        classifier.classify("你好");

        assertNotNull(classifier.lastLlmProperties);
        assertEquals("https://api.example.com/v1", classifier.lastLlmProperties.getBaseUrl());
        assertEquals("test-model", classifier.lastLlmProperties.getChatModel());
        assertEquals("test-key", classifier.lastLlmProperties.getApiKey());
    }

    private static class TestableIntentClassifier extends IntentClassifier {
        private String nextResponse;
        private boolean throwOnCall;
        private LlmProperties lastLlmProperties;

        private TestableIntentClassifier(IntentProperties intentProperties, LlmProperties llmProperties) {
            super(intentProperties, llmProperties);
        }

        @Override
        protected String callLlm(String userMessage, LlmProperties intentLlmProperties) {
            lastLlmProperties = intentLlmProperties;
            if (throwOnCall) {
                throw new RuntimeException("mock llm error");
            }
            return nextResponse;
        }
    }
}

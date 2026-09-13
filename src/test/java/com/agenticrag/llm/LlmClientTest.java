package com.agenticrag.llm;

import com.agenticrag.config.LlmProperties;
import com.agenticrag.llm.dto.ChatMessage;
import com.agenticrag.llm.dto.LlmResponse;
import com.agenticrag.llm.dto.ToolCall;
import com.agenticrag.llm.dto.ToolSchema;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void chatWithToolsSendsToolsInRequest() throws Exception {
        AtomicReference<JsonNode> capturedRequest = new AtomicReference<>();
        try (TestHttpServer server = new TestHttpServer(exchange -> {
            capturedRequest.set(readJson(exchange));
            writeJson(exchange, 200, """
                    {"choices":[{"message":{"content":"","tool_calls":[]}}]}
                    """);
        })) {
            LlmClient client = new LlmClient(properties(server.baseUrl()));

            client.chatWithTools(
                    List.of(ChatMessage.user("查一下 M3 状态机方案")),
                    List.of(new ToolSchema(
                            "search_knowledge_base",
                            "搜索知识库",
                            """
                            {"type":"object","properties":{"query":{"type":"string"}},"required":["query"]}
                            """
                    ))
            );

            JsonNode request = capturedRequest.get();
            assertEquals("test-chat", request.path("model").asText());
            assertTrue(request.has("tools"));
            assertEquals(1, request.path("tools").size());
            JsonNode function = request.path("tools").get(0).path("function");
            assertEquals("search_knowledge_base", function.path("name").asText());
            assertEquals("object", function.path("parameters").path("type").asText());
            assertEquals("query", function.path("parameters").path("required").get(0).asText());
        }
    }

    @Test
    void chatWithToolsParsesToolCallsFromResponse() throws Exception {
        try (TestHttpServer server = new TestHttpServer(exchange -> {
            readJson(exchange);
            writeJson(exchange, 200, """
                    {
                      "choices": [
                        {
                          "message": {
                            "content": "",
                            "tool_calls": [
                              {
                                "id": "call_123",
                                "type": "function",
                                "function": {
                                  "name": "search_knowledge_base",
                                  "arguments": "{\\"query\\":\\"M3 状态机\\"}"
                                }
                              }
                            ]
                          }
                        }
                      ]
                    }
                    """);
        })) {
            LlmClient client = new LlmClient(properties(server.baseUrl()));

            LlmResponse response = client.chatWithTools(
                    List.of(ChatMessage.user("查一下 M3 状态机方案")),
                    List.of(new ToolSchema(
                            "search_knowledge_base",
                            "搜索知识库",
                            """
                            {"type":"object","properties":{"query":{"type":"string"}},"required":["query"]}
                            """
                    ))
            );

            assertEquals("", response.content());
            assertEquals(1, response.toolCalls().size());
            ToolCall toolCall = response.toolCalls().get(0);
            assertEquals("call_123", toolCall.id());
            assertEquals("search_knowledge_base", toolCall.name());
            assertEquals("{\"query\":\"M3 状态机\"}", toolCall.argumentsJson());
        }
    }

    @Test
    void chatWithToolsSupportsToolRoleMessages() throws Exception {
        AtomicReference<JsonNode> capturedRequest = new AtomicReference<>();
        try (TestHttpServer server = new TestHttpServer(exchange -> {
            capturedRequest.set(readJson(exchange));
            writeJson(exchange, 200, """
                    {"choices":[{"message":{"content":"最终答案","tool_calls":[]}}]}
                    """);
        })) {
            LlmClient client = new LlmClient(properties(server.baseUrl()));

            LlmResponse response = client.chatWithTools(
                    List.of(
                            ChatMessage.assistantWithToolCalls("", List.of(
                                    new ToolCall("call_456", "calculator", "{\"expression\":\"23*47+5\"}")
                            )),
                            ChatMessage.tool("call_456", "1086")
                    ),
                    List.of()
            );

            JsonNode messages = capturedRequest.get().path("messages");
            assertEquals(2, messages.size());
            assertTrue(messages.get(0).has("tool_calls"));
            assertEquals("call_456", messages.get(0).path("tool_calls").get(0).path("id").asText());
            assertEquals("calculator", messages.get(0).path("tool_calls").get(0).path("function").path("name").asText());
            assertEquals("call_456", messages.get(1).path("tool_call_id").asText());
            assertEquals("tool", messages.get(1).path("role").asText());
            assertFalse(messages.get(1).has("tool_calls"));
            assertEquals("最终答案", response.content());
        }
    }

    private LlmProperties properties(String baseUrl) {
        LlmProperties properties = new LlmProperties();
        properties.setBaseUrl(baseUrl);
        properties.setApiKey("test-key");
        properties.setChatModel("test-chat");
        properties.setTimeoutSeconds(10);
        return properties;
    }

    private JsonNode readJson(HttpExchange exchange) throws IOException {
        return objectMapper.readTree(exchange.getRequestBody());
    }

    private void writeJson(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        } finally {
            exchange.close();
        }
    }

    private static final class TestHttpServer implements AutoCloseable {
        private final HttpServer server;

        private TestHttpServer(com.sun.net.httpserver.HttpHandler handler) throws IOException {
            this.server = HttpServer.create(new InetSocketAddress(0), 0);
            this.server.createContext("/chat/completions", handler);
            this.server.start();
        }

        private String baseUrl() {
            return "http://localhost:" + server.getAddress().getPort();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}

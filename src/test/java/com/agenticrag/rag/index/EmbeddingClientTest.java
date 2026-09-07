package com.agenticrag.rag.index;

import com.agenticrag.config.LlmProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EmbeddingClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void embedBatchSplitsLargeRequestsIntoBatches() throws Exception {
        AtomicInteger requestCount = new AtomicInteger();
        try (TestHttpServer server = new TestHttpServer(exchange -> {
            requestCount.incrementAndGet();
            JsonNode request = readJson(exchange);
            JsonNode inputs = request.path("input");
            List<List<Float>> vectors = new ArrayList<>();
            for (int i = 0; i < inputs.size(); i++) {
                vectors.add(List.of((float) i, (float) inputs.get(i).asText().length()));
            }
            writeJson(exchange, 200, responseBody(vectors));
        })) {
            EmbeddingClient client = new EmbeddingClient(properties(server.baseUrl()));

            List<String> inputs = new ArrayList<>();
            for (int i = 0; i < 35; i++) {
                inputs.add("doc-" + i);
            }

            List<float[]> vectors = client.embedBatch(inputs);

            assertEquals(35, vectors.size());
            assertEquals(2, requestCount.get());
        }
    }

    @Test
    void embedBatchRetriesAfterTransientFailure() throws Exception {
        AtomicInteger requestCount = new AtomicInteger();
        try (TestHttpServer server = new TestHttpServer(exchange -> {
            int attempt = requestCount.incrementAndGet();
            if (attempt == 1) {
                writeJson(exchange, 500, "{\"error\":{\"message\":\"temporary\"}}");
                return;
            }
            writeJson(exchange, 200, responseBody(List.of(List.of(0.1f, 0.9f))));
        })) {
            EmbeddingClient client = new EmbeddingClient(properties(server.baseUrl()));

            List<float[]> vectors = client.embedBatch(List.of("retry"));

            assertEquals(1, vectors.size());
            assertEquals(2, requestCount.get());
        }
    }

    private LlmProperties properties(String baseUrl) {
        LlmProperties properties = new LlmProperties();
        properties.setBaseUrl(baseUrl);
        properties.setApiKey("test-key");
        properties.setEmbeddingModel("test-embedding");
        properties.setTimeoutSeconds(10);
        return properties;
    }

    private JsonNode readJson(HttpExchange exchange) throws IOException {
        return objectMapper.readTree(exchange.getRequestBody());
    }

    private String responseBody(List<List<Float>> vectors) throws IOException {
        List<Object> data = new ArrayList<>();
        for (int i = 0; i < vectors.size(); i++) {
            data.add(java.util.Map.of(
                    "index", i,
                    "embedding", vectors.get(i)
            ));
        }
        return objectMapper.writeValueAsString(java.util.Map.of("data", data));
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

    private final class TestHttpServer implements AutoCloseable {
        private final HttpServer server;

        private TestHttpServer(com.sun.net.httpserver.HttpHandler handler) throws IOException {
            this.server = HttpServer.create(new InetSocketAddress(0), 0);
            this.server.createContext("/embeddings", handler);
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

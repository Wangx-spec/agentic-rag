package com.agenticrag.rag.retrieve;

import com.agenticrag.config.LlmProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RerankClientTest {

    private HttpServer server;
    private LlmProperties properties;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        properties = new LlmProperties();
        properties.setApiKey("test-key");
        properties.setTimeoutSeconds(2);
        properties.setBaseUrl("http://localhost:" + server.getAddress().getPort());
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void successReturnsRerankedResults() {
        server.createContext("/rerank", exchange -> {
            String body = "{\"results\":[{\"index\":2,\"relevance_score\":0.89},"
                    + "{\"index\":0,\"relevance_score\":0.76},"
                    + "{\"index\":1,\"relevance_score\":0.65}]}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.getResponseBody().close();
        });

        RerankClient client = new RerankClient(properties);

        Optional<List<RerankResult>> result = client.rerank(
                "test query", List.of("doc1", "doc2", "doc3"), 3, "BAAI/bge-reranker-v2-m3");

        assertTrue(result.isPresent());
        List<RerankResult> results = result.get();
        assertEquals(3, results.size());
        assertEquals(2, results.get(0).index());
        assertEquals(0.89, results.get(0).relevanceScore(), 0.001);
        assertEquals(0, results.get(1).index());
        assertEquals(0.76, results.get(1).relevanceScore(), 0.001);
        assertEquals(1, results.get(2).index());
    }

    @Test
    void http4xxReturnsEmpty() {
        server.createContext("/rerank", exchange -> {
            String body = "{\"error\":\"invalid model\"}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(400, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.getResponseBody().close();
        });

        RerankClient client = new RerankClient(properties);

        Optional<List<RerankResult>> result = client.rerank(
                "test query", List.of("doc1", "doc2"), 2, "bad-model");

        assertTrue(result.isEmpty());
    }

    @Test
    void timeoutReturnsEmptyAfterRetry() {
        server.createContext("/rerank", exchange -> {
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        properties.setTimeoutSeconds(1);
        RerankClient client = new RerankClient(properties);

        Optional<List<RerankResult>> result = client.rerank(
                "test query", List.of("doc1", "doc2"), 2, "BAAI/bge-reranker-v2-m3");

        assertTrue(result.isEmpty());
    }

    @Test
    void emptyDocumentsReturnsEmpty() {
        RerankClient client = new RerankClient(properties);
        Optional<List<RerankResult>> result = client.rerank("query", List.of(), 5, "model");
        assertTrue(result.isEmpty());
    }

    @Test
    void notConfiguredReturnsEmpty() {
        LlmProperties unconfigured = new LlmProperties();
        unconfigured.setApiKey("");
        unconfigured.setBaseUrl("http://localhost:" + server.getAddress().getPort());
        RerankClient client = new RerankClient(unconfigured);

        Optional<List<RerankResult>> result = client.rerank(
                "test query", List.of("doc1"), 1, "model");
        assertTrue(result.isEmpty());
    }
}

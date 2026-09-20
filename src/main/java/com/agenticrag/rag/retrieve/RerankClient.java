package com.agenticrag.rag.retrieve;

import com.agenticrag.config.LlmProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Rerank 客户端：调用硅基流动 /v1/rerank 端点，对候选文档按相关性精排。
 * <p>
 * 复用 LlmProperties（baseUrl / apiKey / timeoutSeconds），fail-open 设计：
 * 任何失败路径返回 Optional.empty()，由调用方决定降级策略。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RerankClient {

    private final LlmProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private volatile HttpClient httpClient;

    /**
     * 对候选文档列表重排序。
     *
     * @param query     原始查询
     * @param documents 候选文档文本列表
     * @param topN      返回前 N 个
     * @param model     rerank 模型名
     * @return Optional<List<RerankResult>>，empty 表示调用失败（fail-open）
     */
    public Optional<List<RerankResult>> rerank(String query, List<String> documents,
                                               int topN, String model) {
        if (query == null || query.isBlank() || documents == null || documents.isEmpty()) {
            return Optional.empty();
        }
        if (!properties.isConfigured()) {
            log.warn("Rerank 调用失败：LLM 未配置，回退原序");
            return Optional.empty();
        }

        long start = System.currentTimeMillis();
        int attempt = 0;
        while (true) {
            attempt++;
            try {
                List<RerankResult> results = doRerank(query, documents, topN, model);
                long elapsed = System.currentTimeMillis() - start;
                log.info("Rerank 完成：{} 文档 → top {}，耗时 {}ms", documents.size(), results.size(), elapsed);
                return Optional.of(results);
            } catch (Exception e) {
                if (attempt <= 1 && isNetworkError(e)) {
                    log.warn("Rerank 网络异常，1 秒后重试：{}", e.getMessage());
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                    continue;
                }
                log.warn("Rerank 调用失败：{}，回退原序（query={}）", e.getMessage(), abbreviate(query));
                return Optional.empty();
            }
        }
    }

    private List<RerankResult> doRerank(String query, List<String> documents, int topN, String model) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", model);
        body.put("query", query);
        ArrayNode docsArray = body.putArray("documents");
        for (String doc : documents) {
            docsArray.add(doc == null ? "" : doc);
        }
        body.put("top_n", topN);
        body.put("return_documents", false);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(trimTrailingSlash(properties.getBaseUrl()) + "/rerank"))
                .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                .header("Authorization", "Bearer " + properties.getApiKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = client().send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            String errorBody = response.body() == null ? "" : response.body();
            String truncated = errorBody.length() > 200 ? errorBody.substring(0, 200) : errorBody;
            throw new RuntimeException("HTTP " + response.statusCode() + ": " + truncated);
        }

        JsonNode root = objectMapper.readTree(response.body());
        JsonNode resultsNode = root.path("results");
        if (!resultsNode.isArray()) {
            throw new RuntimeException("Rerank 响应缺少 results 数组");
        }

        List<RerankResult> results = new ArrayList<>();
        for (JsonNode item : resultsNode) {
            int index = item.path("index").asInt(-1);
            double score = item.path("relevance_score").asDouble(0.0);
            results.add(new RerankResult(index, score));
        }
        return results;
    }

    private boolean isNetworkError(Exception e) {
        return e instanceof IOException;
    }

    private String abbreviate(String query) {
        if (query == null) return "null";
        String compact = query.strip().replaceAll("\\s+", " ");
        return compact.length() <= 50 ? compact : compact.substring(0, 50) + "…";
    }

    private HttpClient client() {
        if (httpClient == null) {
            synchronized (this) {
                if (httpClient == null) {
                    httpClient = HttpClient.newBuilder()
                            .version(HttpClient.Version.HTTP_1_1)
                            .connectTimeout(Duration.ofSeconds(15))
                            .build();
                }
            }
        }
        return httpClient;
    }

    private static String trimTrailingSlash(String url) {
        return url == null ? "" : (url.endsWith("/") ? url.substring(0, url.length() - 1) : url);
    }
}

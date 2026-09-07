package com.agenticrag.rag.index;

import com.agenticrag.config.LlmProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Embedding 客户端（OpenAI 兼容 /embeddings，复用 LlmProperties 的 base-url/api-key）
 * <p>
 * 计划方法：
 * - float[] embed(String text)                    单条向量化
 * - List&lt;float[]&gt; embedBatch(List&lt;String&gt; texts)  批量向量化（默认 32/批，失败重试）
 */
@Component
@RequiredArgsConstructor
public class EmbeddingClient {

    private static final int DEFAULT_BATCH_SIZE = 32;
    private static final int MAX_RETRY = 2;

    private final LlmProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private volatile HttpClient httpClient;

    public float[] embed(String text) {
        List<float[]> result = embedBatch(List.of(text));
        if (result.isEmpty()) {
            throw new IllegalStateException("embedding 返回为空");
        }
        return result.get(0);
    }

    public List<float[]> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        if (!properties.isConfigured()) {
            throw new IllegalStateException("LLM 未配置，无法调用 embedding 接口");
        }

        List<float[]> all = new ArrayList<>();
        for (int i = 0; i < texts.size(); i += DEFAULT_BATCH_SIZE) {
            int end = Math.min(i + DEFAULT_BATCH_SIZE, texts.size());
            List<String> batch = texts.subList(i, end);
            all.addAll(embedBatchWithRetry(batch));
        }
        return all;
    }

    private List<float[]> embedBatchWithRetry(List<String> texts) {
        int attempt = 0;
        while (true) {
            try {
                return doEmbedBatch(texts);
            } catch (Exception e) {
                attempt++;
                if (attempt > MAX_RETRY) {
                    throw new IllegalStateException("embedding 调用失败: " + e.getMessage(), e);
                }
            }
        }
    }

    private List<float[]> doEmbedBatch(List<String> texts) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", properties.getEmbeddingModel());

        ArrayNode input = body.putArray("input");
        for (String text : texts) {
            input.add(text == null ? "" : text);
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(trimTrailingSlash(properties.getBaseUrl()) + "/embeddings"))
                .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                .header("Authorization", "Bearer " + properties.getApiKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = client().send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IllegalStateException("embedding 返回 " + response.statusCode() + ": " + response.body());
        }

        JsonNode root = objectMapper.readTree(response.body());
        JsonNode data = root.path("data");
        if (!data.isArray() || data.isEmpty()) {
            throw new IllegalStateException("embedding 响应缺少 data 字段");
        }

        List<float[]> vectors = new ArrayList<>();
        for (JsonNode item : data) {
            JsonNode embedding = item.path("embedding");
            if (!embedding.isArray() || embedding.isEmpty()) {
                throw new IllegalStateException("embedding 响应缺少 embedding 字段");
            }
            float[] vector = new float[embedding.size()];
            for (int i = 0; i < embedding.size(); i++) {
                vector[i] = (float) embedding.get(i).asDouble();
            }
            vectors.add(vector);
        }
        return vectors;
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
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /** 将 embedding 模型名归一化为 collection 名可用的 slug（小写，非字母数字→下划线） */
    public String modelSlug() {
        String model = properties.getEmbeddingModel();
        if (model == null) {
            return "unknown";
        }
        return model.toLowerCase().replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
    }
}

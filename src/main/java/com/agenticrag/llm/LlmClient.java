package com.agenticrag.llm;

import com.agenticrag.config.LlmProperties;
import com.agenticrag.llm.dto.ChatMessage;
import com.agenticrag.llm.dto.LlmResponse;
import com.agenticrag.llm.dto.ToolCall;
import com.agenticrag.llm.dto.ToolSchema;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class LlmClient {

    private static final String SSE_DATA_PREFIX = "data:";
    private static final String SSE_DONE = "[DONE]";

    private final LlmProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private volatile HttpClient httpClient;

    /**
     * 流式对话：逐 delta 回调 onDelta，返回完整回答文本
     *
     * @throws LlmException 请求失败 / 响应异常 / 流中断
     */
    public String chatStream(List<ChatMessage> messages, StreamListener listener) {
        if (!properties.isConfigured()) {
            throw new LlmException("LLM 未配置：请设置 llm.base-url / llm.api-key / llm.model（推荐用环境变量 LLM_API_KEY 注入 key）");
        }
        try {
            HttpResponse<java.io.InputStream> response = send(messages, true, Collections.emptyList());
            if (response.statusCode() != 200) {
                String body = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
                throw new LlmException("LLM 返回 " + response.statusCode() + ": " + extractErrorMessage(body, response.statusCode()));
            }
            return readStream(response.body(), listener);
        } catch (StreamInterruptedException e) {
            // 思考中断，降级同步
            String fallback = chat(messages);
            if (listener != null) {
                // 发送空 delta 触发前端从思考模式切换到回答模式
                if (!fallback.isEmpty()) {
                    listener.onAnswer(fallback);
                }
            }
            return fallback;
        } catch (LlmException e) {
            throw e;
        } catch (Exception e) {
            throw new LlmException("LLM 调用失败: " + e.getMessage(), e);
        }
    }

    /**
     * 同步对话（M3 Agent 循环 / 评测使用）
     */
    public String chat(List<ChatMessage> messages) {
        if (!properties.isConfigured()) {
            throw new LlmException("LLM 未配置");
        }
        try {
            HttpResponse<java.io.InputStream> response = send(messages, false, Collections.emptyList());
            if (response.statusCode() != 200) {
                String body = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
                throw new LlmException("LLM 返回 " + response.statusCode() + ": " + extractErrorMessage(body, response.statusCode()));
            }
            JsonNode root = objectMapper.readTree(response.body());
            return root.path("choices").path(0).path("message").path("content").asText("");
        } catch (LlmException e) {
            throw e;
        } catch (Exception e) {
            throw new LlmException("LLM 调用失败: " + e.getMessage(), e);
        }
    }

    public LlmResponse chatWithTools(List<ChatMessage> messages, List<ToolSchema> tools) {
        if (!properties.isConfigured()) {
            throw new LlmException("LLM 未配置");
        }
        try {
            HttpResponse<java.io.InputStream> response = send(
                    messages,
                    false,
                    tools == null ? Collections.emptyList() : tools
            );
            if (response.statusCode() != 200) {
                String body = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
                throw new LlmException("LLM 返回 " + response.statusCode() + ": " + extractErrorMessage(body, response.statusCode()));
            }
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode messageNode = root.path("choices").path(0).path("message");
            String content = messageNode.path("content").asText("");
            List<ToolCall> toolCalls = parseToolCalls(messageNode.path("tool_calls"));
            return new LlmResponse(content, toolCalls);
        } catch (LlmException e) {
            throw e;
        } catch (Exception e) {
            throw new LlmException("LLM 调用失败: " + e.getMessage(), e);
        }
    }

    // ==================== 内部实现 ====================

    private HttpResponse<java.io.InputStream> send(List<ChatMessage> messages, boolean stream, List<ToolSchema> tools) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", properties.getChatModel());
        body.put("stream", stream);
        ArrayNode messageNodes = body.putArray("messages");
        for (ChatMessage message : messages) {
            ObjectNode node = messageNodes.addObject();
            node.put("role", message.role());
            node.put("content", message.content());
            if (message.toolCallId() != null && !message.toolCallId().isBlank()) {
                node.put("tool_call_id", message.toolCallId());
            }
            if (message.toolCalls() != null && !message.toolCalls().isEmpty()) {
                ArrayNode toolCallNodes = node.putArray("tool_calls");
                for (ToolCall toolCall : message.toolCalls()) {
                    ObjectNode toolCallNode = toolCallNodes.addObject();
                    toolCallNode.put("id", toolCall.id());
                    toolCallNode.put("type", "function");
                    ObjectNode functionNode = toolCallNode.putObject("function");
                    functionNode.put("name", toolCall.name());
                    functionNode.put("arguments", toolCall.argumentsJson());
                }
            }
        }

        if (tools != null && !tools.isEmpty()) {
            ArrayNode toolNodes = body.putArray("tools");
            for (ToolSchema tool : tools) {
                ObjectNode toolNode = toolNodes.addObject();
                toolNode.put("type", "function");
                ObjectNode functionNode = toolNode.putObject("function");
                functionNode.put("name", tool.name());
                functionNode.put("description", tool.description());
                functionNode.set("parameters", objectMapper.readTree(tool.parametersSchema()));
            }
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(trimTrailingSlash(properties.getBaseUrl()) + "/chat/completions"))
                .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                .header("Authorization", "Bearer " + properties.getApiKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();

        return client().send(request, HttpResponse.BodyHandlers.ofInputStream());
    }

    private String readStream(java.io.InputStream inputStream, StreamListener listener) throws IOException {
        StringBuilder full = new StringBuilder();
        int reasoningChunks = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (!line.startsWith(SSE_DATA_PREFIX)) {
                    continue;
                }
                String payload = line.substring(SSE_DATA_PREFIX.length()).trim();
                if (payload.isEmpty()) {
                    continue;
                }
                if (SSE_DONE.equals(payload)) {
                    break;
                }
                JsonNode node = objectMapper.readTree(payload);
                // 兼容错误载荷（部分网关在流内返回 {"error": ...}）
                if (node.has("error")) {
                    throw new LlmException("LLM 流式返回错误: " + node.path("error").path("message").asText("unknown"));
                }
                String reasoning = node.path("choices").path(0).path("delta").path("reasoning_content").asText("");
                if (!reasoning.isEmpty()) {
                    reasoningChunks++;
                    if (listener != null) {
                        listener.onThinking(reasoning);
                    }
                }
                String delta = node.path("choices").path(0).path("delta").path("content").asText("");
                if (!delta.isEmpty()) {
                    full.append(delta);
                    if (listener != null) {
                        listener.onAnswer(delta);
                    }
                }
            }
        } catch (IOException e) {
            if (!full.isEmpty()) {
                return full.toString();
            }
            if (reasoningChunks > 0) {
                throw new StreamInterruptedException(reasoningChunks, e);
            }
            throw e;
        }
        if (full.isEmpty()) {
            throw new LlmException("LLM 返回了空内容");
        }
        return full.toString();
    }

    private List<ToolCall> parseToolCalls(JsonNode toolCallsNode) {
        if (toolCallsNode == null || !toolCallsNode.isArray()) {
            return Collections.emptyList();
        }
        List<ToolCall> toolCalls = new ArrayList<>();
        for (JsonNode toolCallNode : toolCallsNode) {
            String id = toolCallNode.path("id").asText("");
            JsonNode functionNode = toolCallNode.path("function");
            String name = functionNode.path("name").asText("");
            String argumentsJson = functionNode.path("arguments").asText("");
            toolCalls.add(new ToolCall(id, name, argumentsJson));
        }
        return toolCalls;
    }

    private String extractErrorMessage(String body, int statusCode) {
        try {
            JsonNode error = objectMapper.readTree(body).path("error").path("message");
            if (!error.isMissingNode() && !error.asText().isBlank()) {
                return error.asText();
            }
        } catch (Exception ignored) {
            // 解析失败则原样截断返回
        }
        return body.length() > 300 ? body.substring(0, 300) + "..." : body;
    }

    private static String trimTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
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

    /** LLM 调用异常（携带用户可读信息） */
    public static class LlmException extends RuntimeException {
        public LlmException(String message) {
            super(message);
        }

        public LlmException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static class StreamInterruptedException extends IOException {
        private StreamInterruptedException(int reasoningChunks, Throwable cause) {
            super("stream interrupted after reasoning-only phase", cause);
        }
    }

    public interface StreamListener {
        default void onThinking(String delta) {
        }

        default void onAnswer(String delta) {
        }
    }
}

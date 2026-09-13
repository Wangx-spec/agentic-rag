package com.agenticrag.multiagent;

import com.agenticrag.llm.LlmClient;
import com.agenticrag.llm.dto.ChatMessage;
import com.agenticrag.multiagent.dto.SubTaskResult;
import com.agenticrag.rag.retrieve.RetrievedChunk;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Aggregator：汇总成功子任务的中间结论，并流式生成最终回答。
 */
@Component
@RequiredArgsConstructor
public class Aggregator {

    private static final String AGGREGATE_PROMPT_TEMPLATE = """
            你是一个基于给定材料回答问题的中文助手。请根据“子任务结论”和“支撑片段”生成对原问题的最终回答。

            要求：
            1. 优先综合各子任务结论，按用户原问题的结构组织答案
            2. 只能使用给定支撑片段中的信息做引用，引用格式必须是 [n]
            3. 如果不同子结论之间存在冲突或材料不足，请明确说明
            4. 不要提及“子任务”“工作流”“多 Agent”等实现细节
            5. 回答保持简洁清晰

            原问题：
            %s

            子任务结论：
            %s

            支撑片段：
            %s
            """;

    private final LlmClient llmClient;

    public AggregateResult aggregate(String question, List<SubTaskResult> results, SseEmitter emitter) {
        List<RetrievedChunk> mergedSources = mergeSources(results);
        String prompt = buildAggregatePrompt(question, results, mergedSources);
        String answer = llmClient.chatStream(List.of(ChatMessage.system(prompt)), new LlmClient.StreamListener() {
            @Override
            public void onAnswer(String delta) {
                send(emitter, "delta", Map.of("text", delta));
            }
        });
        return new AggregateResult(answer, mergedSources);
    }

    private String buildAggregatePrompt(String question, List<SubTaskResult> results, List<RetrievedChunk> mergedSources) {
        StringBuilder subConclusions = new StringBuilder();
        for (SubTaskResult result : results) {
            subConclusions.append(result.index())
                    .append(". 问题：")
                    .append(result.question())
                    .append("\n结论：")
                    .append(result.conclusion())
                    .append("\n\n");
        }

        StringBuilder context = new StringBuilder();
        for (RetrievedChunk chunk : mergedSources) {
            context.append("[")
                    .append(chunk.rank())
                    .append("] ")
                    .append(chunk.docName())
                    .append("：")
                    .append(chunk.content())
                    .append("\n\n");
        }

        return AGGREGATE_PROMPT_TEMPLATE.formatted(
                question,
                subConclusions.toString().trim(),
                context.toString().trim()
        );
    }

    private List<RetrievedChunk> mergeSources(List<SubTaskResult> results) {
        Map<String, RetrievedChunk> deduped = new LinkedHashMap<>();
        for (SubTaskResult result : results) {
            for (RetrievedChunk chunk : result.sources()) {
                String key = chunk.chunkId() != null
                        ? "chunk-" + chunk.chunkId()
                        : chunk.documentId() + "-" + chunk.seq();
                deduped.putIfAbsent(key, chunk);
            }
        }

        int rank = 1;
        List<RetrievedChunk> merged = new java.util.ArrayList<>(deduped.size());
        for (RetrievedChunk chunk : deduped.values()) {
            merged.add(new RetrievedChunk(
                    chunk.chunkId(),
                    chunk.documentId(),
                    chunk.seq(),
                    chunk.content(),
                    chunk.docName(),
                    chunk.rrfScore(),
                    rank++
            ));
        }
        return merged;
    }

    private void send(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data));
        } catch (IOException ignored) {
            // 与 ChatController 的 SSE 发送策略保持一致：发送失败时交给容器回调处理
        }
    }

    public record AggregateResult(String answer, List<RetrievedChunk> sources) {
    }
}

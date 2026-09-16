package com.agenticrag.multiagent;

import com.agenticrag.llm.dto.ChatMessage;
import com.agenticrag.multiagent.dto.SubTaskResult;
import com.agenticrag.service.ChatEventSink;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import com.agenticrag.memory.ConversationMemory;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

@Slf4j
@Component
public class MultiAgentOrchestrator {
    
    private final LeaderAgent leaderAgent;
    private final SubAgentExecutor subAgentExecutor;
    private final Aggregator aggregator;
    private final ConversationMemory memory;
    private final Executor multiAgentExecutor;   // 注意：Executor，不是 ExecutorService

    public MultiAgentOrchestrator(LeaderAgent leaderAgent,
                                  SubAgentExecutor subAgentExecutor,
                                  Aggregator aggregator,
                                  ConversationMemory memory,
                                  @Qualifier("multiAgentExecutor") Executor multiAgentExecutor) {
        this.leaderAgent = leaderAgent;
        this.subAgentExecutor = subAgentExecutor;
        this.aggregator = aggregator;
        this.memory = memory;
        this.multiAgentExecutor = multiAgentExecutor;
    }

    /**
     * 执行多 Agent 编排。
     *
     * @return true 表示已由多 Agent 链路完成回答；false 表示调用方应降级走普通 RAG
     */
    public boolean orchestrate(String question, ChatEventSink sink, String sessionId) {
        List<String> subQuestions = leaderAgent.plan(question);
        if (subQuestions.size() <= 1) {
            log.info("多 Agent 降级：拆解结果仅 {} 个子问题", subQuestions.size());
            return false;
        }

        emitThinking(sink, "[多Agent] 拆解完成：" + subQuestions.size() + " 个子问题");
        for (int i = 0; i < subQuestions.size(); i++) {
            emitThinking(sink, "[多Agent] 子任务 " + (i + 1) + " 开始：" + subQuestions.get(i));
        }

        List<SubTaskResult> results = executeSubTasksInParallel(subQuestions);
        for (SubTaskResult result : results) {
            if (result.success()) {
                emitThinking(sink, "[多Agent] 子任务 " + result.index() + " 完成");
            } else {
                emitThinking(sink, "[多Agent] 子任务 " + result.index() + " 失败：" + result.error());
            }
        }

        List<SubTaskResult> successful = filterSuccessful(results);
        if (successful.isEmpty()) {
            log.info("多 Agent 降级：全部子任务执行失败");
            return false;
        }

        emitThinking(sink, "[多Agent] 开始汇总回答");
        Aggregator.AggregateResult aggregateResult = aggregator.aggregate(question, successful, sink);
        memory.append(sessionId, ChatMessage.assistant(aggregateResult.answer()));
        sink.onDone(aggregateResult.sources());
        return true;
    }

    private void emitThinking(ChatEventSink sink, String text) {
        sink.onThinking(text);
    }

    private List<SubTaskResult> executeSubTasksInParallel(List<String> subQuestions) {
        List<CompletableFuture<SubTaskResult>> futures = new ArrayList<>(subQuestions.size());
        for (int i = 0; i < subQuestions.size(); i++) {
            final int index = i + 1;
            final String subQuestion = subQuestions.get(i);
            futures.add(CompletableFuture
                    .supplyAsync(() -> subAgentExecutor.execute(subQuestion, index), multiAgentExecutor)
                    .exceptionally(ex -> {
                        log.warn("子任务 {} 异常结束", index, ex);
                        return SubTaskResult.failure(index, subQuestion, ex.getMessage());
                    }));
        }

        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
        return futures.stream().map(CompletableFuture::join).toList();
    }

    private List<SubTaskResult> filterSuccessful(List<SubTaskResult> results) {
        return results.stream()
                .filter(SubTaskResult::success)
                .filter(result -> result.conclusion() != null && !result.conclusion().isBlank())
                .toList();
    }

}

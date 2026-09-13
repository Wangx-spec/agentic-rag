package com.agenticrag.multiagent.dto;

import com.agenticrag.rag.retrieve.RetrievedChunk;

import java.util.Collections;
import java.util.List;

/**
 * 子任务执行结果：SubAgentExecutor 产出，Aggregator 消费
 * <p>
 * 失败不抛异常，以 {@code success=false} + {@code error} 标记，
 * 保证单个子任务失败不阻塞整体编排。
 *
 * @param index      子任务序号（从 1 开始，用于 SSE 事件与日志）
 * @param question   子问题文本
 * @param conclusion 子结论（带 [n] 引用），失败时为 null
 * @param sources    该子问题命中的检索片段，供汇总时合并去重
 * @param success    是否成功
 * @param error      失败原因，成功时为 null
 */
public record SubTaskResult(
        int index,
        String question,
        String conclusion,
        List<RetrievedChunk> sources,
        boolean success,
        String error
) {

    public SubTaskResult {
        sources = sources == null ? Collections.emptyList() : List.copyOf(sources);
    }

    public static SubTaskResult ok(int index, String question, String conclusion, List<RetrievedChunk> sources) {
        return new SubTaskResult(index, question, conclusion, sources, true, null);
    }

    public static SubTaskResult failure(int index, String question, String error) {
        return new SubTaskResult(index, question, null, Collections.emptyList(), false, error);
    }
}

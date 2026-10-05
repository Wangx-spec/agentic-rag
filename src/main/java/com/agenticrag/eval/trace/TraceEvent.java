package com.agenticrag.eval.trace;

import java.util.List;

/**
 * S3.3：一条 run_trace 轨迹事件。
 */
public record TraceEvent(
        String runId,
        String questionId,
        int turn,
        String eventType,
        String toolName,
        String toolArgsSummary,
        String resultSummary,
        List<String> evidenceIds,
        List<String> evidenceTypes,
        String routedIntent,
        String criticVerdict,
        Integer tokensUsed
) {

    public TraceEvent {
        evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
        evidenceTypes = evidenceTypes == null ? List.of() : List.copyOf(evidenceTypes);
    }

    public static TraceEvent of(String eventType, int turn) {
        EvalTraceContext ctx = EvalTraceContext.current();
        if (ctx == null) {
            return null;
        }
        return new TraceEvent(ctx.runId(), ctx.questionId(), turn, eventType,
                null, null, null, List.of(), List.of(), null, null, null);
    }
}

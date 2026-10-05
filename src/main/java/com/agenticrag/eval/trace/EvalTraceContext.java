package com.agenticrag.eval.trace;

/**
 * S3.3：评测运行上下文。普通聊天没有上下文时 TraceRecorder 不记录事件。
 */
public record EvalTraceContext(String runId, String questionId) {

    private static final ThreadLocal<EvalTraceContext> CURRENT = new ThreadLocal<>();

    public static void set(String runId, String questionId) {
        CURRENT.set(new EvalTraceContext(runId, questionId));
    }

    public static EvalTraceContext current() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }
}

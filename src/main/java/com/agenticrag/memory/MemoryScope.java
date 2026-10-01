package com.agenticrag.memory;

/**
 * 记忆请求上下文载体（M9/T9）：在单次聊天请求调用链内传递 userId + sessionId。
 * <p>
 * Agent 工具接口 {@code Tool.execute(Map)} 不携带会话参数，记忆类工具（如 SearchMemoryTool）
 * 通过本 ThreadLocal 读取当前用户以实现 userId 隔离（N3）。
 * <p>
 * 持有请求上下文的入口（ChatService）必须 try-finally 成对 set/clear；
 * 非请求线程（多 Agent 子任务、定时任务）无上下文，工具侧需优雅降级（N1）。
 * 注意：上下文不跨线程池自动传递（multiAgentExecutor 场景见工具降级提示）；
 * 读取历史只依赖入参 userId，不受此限制。
 */
public final class MemoryScope {

    /** 单次请求的会话上下文：userId 由入口统一指定，sessionId 为实际会话标识 */
    public record Context(long userId, String sessionId) {
    }

    private static final ThreadLocal<Context> CURRENT = new ThreadLocal<>();

    private MemoryScope() {
    }

    public static void set(long userId, String sessionId) {
        CURRENT.set(new Context(userId, sessionId));
    }

    /** 当前请求上下文；非请求调用链返回 null，调用方需自行降级 */
    public static Context current() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }
}

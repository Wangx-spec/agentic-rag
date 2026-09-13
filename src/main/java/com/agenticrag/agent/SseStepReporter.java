package com.agenticrag.agent;

import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Agent 过程事件 → SSE thinking 通道推送。
 * <p>
 * 复用 M2 前端已有的 thinking 展示机制，实现零改动：
 * 每个事件一条 {@code event:thinking}，payload 为 {text: "..."}。
 */
public class SseStepReporter implements StepReporter {
    
    private final BiConsumer<String, Object> sender;

    public SseStepReporter(BiConsumer<String, Object> sender) {
        this.sender = sender;
    }

    @Override 
    public void onThinking(String toolName) {
        send("⚙️ 思考：判断需要调用工具 " + toolName);
    }

    @Override
    public void onActing(String toolName, String arguments) {
        send("🔧 调用工具：" + toolName + "(" + arguments + ")");
    }

    @Override
    public void onObserving(String summary) {
        send("👁 观察：" + summary);
    }

    @Override
    public void onFinal(String message) {
        send("✅ " + message);
    }

    private void send(String text) {
        sender.accept("thinking", Map.of("text", text));
    }

}

package com.agenticrag.agent;

import com.agenticrag.config.RagProperties;
import org.springframework.stereotype.Component;

/**
 * S3.2：Agent 多步循环资源治理。
 */
@Component
public class BudgetGuard {

    private final RagProperties ragProperties;

    public BudgetGuard(RagProperties ragProperties) {
        this.ragProperties = ragProperties;
    }

    public boolean enabled() {
        return ragProperties.getAgent() == null
                || ragProperties.getAgent().getBudget() == null
                || ragProperties.getAgent().getBudget().isEnabled();
    }

    public String truncate(String toolName, String result) {
        if (!enabled() || result == null || result.isBlank()) {
            return result;
        }
        int maxChars = maxCharsFor(toolName);
        if (maxChars <= 0 || result.length() <= maxChars) {
            return result;
        }
        return result.substring(0, maxChars)
                + "\n\n…已截断（原始 " + result.length() + " 字符，仅展示前 " + maxChars + " 字符）";
    }

    public boolean shouldFinalize(AgentContext ctx) {
        if (!enabled() || ctx == null) {
            return false;
        }
        RagProperties.Budget budget = budget();
        if (ctx.getToolCallCount() >= budget.getMaxToolCallsPerSession()) {
            return true;
        }
        return estimatedTokens(ctx) >= budget.getMaxTotalTokens();
    }

    public String finalizeInstruction(AgentContext ctx) {
        if (ctx != null && ctx.getToolCallCount() >= budget().getMaxToolCallsPerSession()) {
            return "资源预算提示：已达到工具调用次数上限，请基于已有证据收尾，不要继续调用工具。";
        }
        return "资源预算提示：上下文预算已接近上限，请基于已有证据收尾，不要继续调用工具。";
    }

    private int maxCharsFor(String toolName) {
        RagProperties.Budget budget = budget();
        if ("run_sql".equals(toolName) || "list_tables".equals(toolName)) {
            return budget.getSqlMaxChars();
        }
        if ("search_knowledge_base".equals(toolName) || "get_document".equals(toolName)) {
            return budget.getRetrievalMaxChars();
        }
        return budget.getRetrievalMaxChars();
    }

    private int estimatedTokens(AgentContext ctx) {
        int chars = ctx.getMessages().stream()
                .mapToInt(message -> message.content() == null ? 0 : message.content().length())
                .sum();
        return Math.max(1, chars / 4);
    }

    private RagProperties.Budget budget() {
        return ragProperties.getAgent().getBudget();
    }
}

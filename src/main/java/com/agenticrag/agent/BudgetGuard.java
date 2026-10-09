package com.agenticrag.agent;

import com.agenticrag.config.RagProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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
        String fair = fairTruncateByLines(result, maxChars);
        if (fair != null) {
            return fair + "\n\n…已截断（原始 " + result.length() + " 字符，仅展示前 "
                    + maxChars + " 字符，已按记录公平截断）";
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

    private String fairTruncateByLines(String result, int maxChars) {
        if (!result.contains("\n")) {
            return null;
        }
        List<String> records = Arrays.stream(result.split("\\R", -1))
                .filter(line -> !line.isBlank())
                .toList();
        if (records.size() < 2) {
            return null;
        }
        int separatorBudget = Math.max(0, records.size() - 1);
        int contentBudget = maxChars - separatorBudget;
        if (contentBudget < records.size()) {
            return null;
        }
        int[] allocation = allocateFairly(records, contentBudget);
        List<String> truncated = new ArrayList<>(records.size());
        boolean changed = false;
        for (int i = 0; i < records.size(); i++) {
            String record = records.get(i);
            int keep = Math.min(record.length(), allocation[i]);
            changed |= keep < record.length();
            truncated.add(record.substring(0, keep));
        }
        return changed ? String.join("\n", truncated) : null;
    }

    private int[] allocateFairly(List<String> records, int budget) {
        int n = records.size();
        int[] allocation = new int[n];
        boolean[] settled = new boolean[n];
        int remainingBudget = budget;
        int remainingRecords = n;
        while (remainingRecords > 0) {
            int waterline = Math.max(1, remainingBudget / remainingRecords);
            boolean settledAny = false;
            for (int i = 0; i < n; i++) {
                if (!settled[i] && records.get(i).length() <= waterline) {
                    allocation[i] = records.get(i).length();
                    remainingBudget -= allocation[i];
                    settled[i] = true;
                    remainingRecords--;
                    settledAny = true;
                }
            }
            if (!settledAny) {
                for (int i = 0; i < n; i++) {
                    if (!settled[i]) {
                        allocation[i] = Math.max(1, waterline);
                    }
                }
                break;
            }
        }
        return allocation;
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

package com.agenticrag.memory.summary;

import com.agenticrag.llm.dto.ChatMessage;
import com.agenticrag.memory.ConversationMemory;
import com.agenticrag.memory.MemoryLlmClient;
import com.agenticrag.memory.MemoryProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@ConditionalOnProperty(prefix = "rag.memory", name = "type", havingValue = "jdbc")
public class SummaryService {

    private static final String SUMMARY_PREFIX = "[历史摘要] ";
    private static final String SYSTEM_PROMPT = """
            你是对话摘要助手。把「已有摘要」与「新增对话」合并为一份不超过 %d 字的连续摘要，
            保留关键事实、用户偏好与结论性信息，丢弃寒暄与重复内容。仅输出摘要正文，不要输出其他文字。
            """;

    private final ConversationMemory conversationMemory;
    private final ConversationSummaryRepository repository;
    private final MemoryLlmClient memoryLlmClient;
    private final MemoryProperties properties;
    private final Map<String, Boolean> inflight = new ConcurrentHashMap<>();

    public SummaryService(ConversationMemory conversationMemory,
                          ConversationSummaryRepository repository,
                          MemoryLlmClient memoryLlmClient,
                          MemoryProperties properties) {
        this.conversationMemory = conversationMemory;
        this.repository = repository;
        this.memoryLlmClient = memoryLlmClient;
        this.properties = properties;
    }

    /**
     * append 后异步调用：半窗口重叠判定通过后生成增量摘要并落库。
     * 任何异常静默降级（N1），inflight 期间重复调用直接返回（N5）。
     */
    public void compressIfNeeded(long userId, String sessionId) {
        if (!properties.getSummary().isEnabled()) {
            return;
        }
        String key = userId + ":" + sessionId;
        if (inflight.putIfAbsent(key, Boolean.TRUE) != null) {
            return;
        }
        try {
            doCompress(userId, sessionId);
        } catch (Exception e) {
            log.warn("滑动摘要生成失败，跳过本轮: userId={}, sessionId={}", userId, sessionId, e);
        } finally {
            inflight.remove(key);
        }
    }

    /**
     * 拼装「摘要(若有) + 窗口内原文」的消息历史。
     * 读取失败时退化为纯窗口原文（N1）。
     */
    public List<ChatMessage> loadWithHistory(long userId, String sessionId, int keepTurns) {
        List<ChatMessage> window = conversationMemory.load(userId, sessionId, keepTurns * 2);
        try {
            return repository.find(userId, sessionId)
                    .filter(summary -> !summary.content().isBlank())
                    .map(summary -> prependSummary(window, summary.content()))
                    .orElse(window);
        } catch (Exception e) {
            log.warn("会话摘要读取失败，退化为纯窗口历史: userId={}, sessionId={}", userId, sessionId, e);
            return window;
        }
    }

    /**
     * 会话清除联动：删除滚动摘要（半窗口判定基准随行删除）。
     * 异常静默降级（N1），不影响消息清除主路径。
     */
    public void clear(long userId, String sessionId) {
        try {
            repository.delete(userId, sessionId);
        } catch (Exception e) {
            log.warn("会话摘要清除失败: userId={}, sessionId={}", userId, sessionId, e);
        }
    }

    private void doCompress(long userId, String sessionId) {
        int windowSize = properties.getKeepTurns() * 2;
        Long earliestInWindow = repository.earliestMessageIdInWindow(userId, sessionId, windowSize);
        if (earliestInWindow == null) {
            return;
        }
        long lastCovered = repository.find(userId, sessionId)
                .map(ConversationSummaryRepository.SummaryRow::lastMessageId)
                .orElse(0L);
        if (lastCovered >= earliestInWindow) {
            // 上次摘要位置仍在保留窗口内，重叠段未滑出，不触发
            return;
        }
        Long targetId = repository.midWindowMessageId(userId, sessionId, windowSize);
        if (targetId == null || targetId <= lastCovered) {
            return;
        }
        List<ConversationSummaryRepository.MessageSlice> newMessages =
                repository.messagesInRange(userId, sessionId, lastCovered, targetId);
        if (newMessages.isEmpty()) {
            return;
        }

        String existingSummary = repository.find(userId, sessionId)
                .map(ConversationSummaryRepository.SummaryRow::content)
                .orElse("");
        String merged = callLlm(existingSummary, newMessages);
        if (merged == null || merged.isBlank()) {
            return;
        }
        repository.save(userId, sessionId, truncate(merged), targetId);
        log.info("滑动摘要已更新: userId={}, sessionId={}, coveredTo={}, length={}",
                userId, sessionId, targetId, merged.length());
    }

    private String callLlm(String existingSummary, List<ConversationSummaryRepository.MessageSlice> newMessages) {
        StringBuilder user = new StringBuilder();
        user.append("已有摘要：").append(existingSummary.isBlank() ? "（无）" : existingSummary).append("\n\n新增对话：\n");
        for (ConversationSummaryRepository.MessageSlice m : newMessages) {
            user.append(m.role()).append("：").append(m.content()).append("\n");
        }
        return memoryLlmClient.chat(List.of(
                ChatMessage.system(String.format(SYSTEM_PROMPT, properties.getSummary().getMaxChars())),
                ChatMessage.user(user.toString())));
    }

    private String truncate(String summary) {
        int maxChars = properties.getSummary().getMaxChars();
        return summary.length() <= maxChars ? summary : summary.substring(summary.length() - maxChars);
    }

    private List<ChatMessage> prependSummary(List<ChatMessage> window, String summaryContent) {
        List<ChatMessage> withSummary = new ArrayList<>(window.size() + 1);
        withSummary.add(ChatMessage.system(SUMMARY_PREFIX + summaryContent));
        withSummary.addAll(window);
        return withSummary;
    }
}

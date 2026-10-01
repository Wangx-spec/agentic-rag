package com.agenticrag.memory;

import com.agenticrag.llm.dto.ChatMessage;
import com.agenticrag.memory.extract.MemoryExtractionService;
import com.agenticrag.memory.longterm.LongTermMemoryService;
import com.agenticrag.memory.summary.SummaryService;
import com.agenticrag.memory.entity.EntityMemoryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.Executor;

/**
 * 记忆读编排 + 写回调度门面（M9/T8）。
 * <p>
 * 本类始终装配（无 @ConditionalOnProperty），向 ChatService 提供统一记忆入口；
 * jdbc 域的四个服务（摘要/实体/长期/提取）按需可选注入（required=false），
 * 因此 memory/redis 模式自动退化为「纯窗口历史读 + 空记忆片段」，上层完全无感（N1）。
 */
@Slf4j
@Service
public class MemoryContextAssembler {

    private final ConversationMemory conversationMemory;
    private final SummaryService summaryService;
    private final EntityMemoryService entityMemoryService;
    private final LongTermMemoryService longTermMemoryService;
    private final MemoryExtractionService memoryExtractionService;
    private final MemoryProperties properties;
    private final Executor memoryExecutor;

    public MemoryContextAssembler(ConversationMemory conversationMemory,
                                  @Autowired(required = false) SummaryService summaryService,
                                  @Autowired(required = false) EntityMemoryService entityMemoryService,
                                  @Autowired(required = false) LongTermMemoryService longTermMemoryService,
                                  @Autowired(required = false) MemoryExtractionService memoryExtractionService,
                                  MemoryProperties properties,
                                  @Qualifier("memoryExecutor") Executor memoryExecutor) {
        this.conversationMemory = conversationMemory;
        this.summaryService = summaryService;
        this.entityMemoryService = entityMemoryService;
        this.longTermMemoryService = longTermMemoryService;
        this.memoryExtractionService = memoryExtractionService;
        this.properties = properties;
        this.memoryExecutor = memoryExecutor;
    }

    /**
     * 历史读编排：jdbc 模式返回「滚动摘要（若有）+ 窗口内原文」，其余模式与
     * 直接 memory.load 语义一致（仅窗口原文）。双重降级（N1）：
     * 摘要服务异常回退纯窗口，纯窗口也异常则返回空历史，绝不阻断主链路。
     *
     * @param turns 轮数（内部按 turns*2 折算为消息条数）
     */
    public List<ChatMessage> loadHistory(long userId, String sessionId, int turns) {
        if (summaryService != null) {
            try {
                return summaryService.loadWithHistory(userId, sessionId, turns);
            } catch (Exception e) {
                log.warn("摘要历史编排失败，回退纯窗口历史: userId={}, sessionId={}", userId, sessionId, e);
            }
        }
        try {
            return conversationMemory.load(userId, sessionId, turns * 2);
        } catch (Exception e) {
            log.warn("窗口历史读取失败，返回空历史（fail-open）: userId={}, sessionId={}", userId, sessionId, e);
            return List.of();
        }
    }

    /**
     * 记忆片段渲染：实体画像 + 长期记忆语义检索（topk 取 ltm-search-topk）。
     * 无内容或异常返回空串，拼装侧空串则不注入 system 提示词。
     */
    public String buildMemorySection(long userId, String query) {
        try {
            String section = "";
            if (entityMemoryService != null) {
                String profile = entityMemoryService.loadPromptSection(userId);
                if (profile != null && !profile.isBlank()) {
                    section = profile;
                }
            }
            if (longTermMemoryService != null && query != null && !query.isBlank()) {
                List<String> hits = longTermMemoryService.search(userId, query, properties.getLtmSearchTopk());
                if (!hits.isEmpty()) {
                    StringBuilder sb = new StringBuilder(section);
                    if (!sb.isEmpty()) {
                        sb.append('\n');
                    }
                    sb.append("## 相关长期记忆\n");
                    for (int i = 0; i < hits.size(); i++) {
                        sb.append('[').append(i + 1).append("] ").append(hits.get(i)).append('\n');
                    }
                    section = sb.toString();
                }
            }
            return section;
        } catch (Exception e) {
            log.warn("记忆片段渲染失败，本轮不注入记忆上下文: userId={}", userId, e);
            return "";
        }
    }

    /**
     * 每轮回答完成后的异步写回调度：摘要压缩投递到 memoryExecutor（不阻塞请求线程），
     * 提取服务只做 O(1) 计数记账。全程 fail-open，失败仅记日志。
     */
    public void onTurnCompleted(long userId, String sessionId) {
        try {
            if (summaryService != null) {
                SummaryService summary = summaryService;
                memoryExecutor.execute(() -> summary.compressIfNeeded(userId, sessionId));
            }
            if (memoryExtractionService != null) {
                memoryExtractionService.onTurnCompleted(userId, sessionId);
            }
        } catch (Exception e) {
            log.warn("记忆写回调度失败，跳过本轮: userId={}, sessionId={}", userId, sessionId, e);
        }
    }

    /**
     * 会话级清除联动：清滚动摘要与轮数计数。
     * 注意：实体画像与长期记忆是跨会话资产，不随单会话清除。
     */
    public void clearSessionMemory(long userId, String sessionId) {
        try {
            if (summaryService != null) {
                summaryService.clear(userId, sessionId);
            }
            if (memoryExtractionService != null) {
                memoryExtractionService.reset(userId, sessionId);
            }
        } catch (Exception e) {
            log.warn("会话记忆清除联动失败: userId={}, sessionId={}", userId, sessionId, e);
        }
    }
}

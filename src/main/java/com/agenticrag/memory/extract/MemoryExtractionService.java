package com.agenticrag.memory.extract;

import com.agenticrag.llm.dto.ChatMessage;
import com.agenticrag.memory.ConversationMemory;
import com.agenticrag.memory.MemoryLlmClient;
import com.agenticrag.memory.MemoryProperties;
import com.agenticrag.memory.entity.EntityMemoryService;
import com.agenticrag.memory.longterm.LongTermMemoryService;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 周期批量记忆写回（M9/T10）：按会话累计已完成轮数，每 intervalTurns 轮用小模型
 * 「判断+提取」一次，将实体记忆（用户画像键值）与长期记忆（结论性事实）写回存储。
 * <p>
 * 写回不占请求线程：请求侧只做 O(1) 的计数+脏标记，实际 LLM 提取由 @Scheduled
 * 扫描线程串行执行，天然避免同会话并发提取；单会话提取失败仅跳过本轮（N1）。
 * <p>
 * 已知取舍：计数器为进程内状态，重启后归零，首次提取至多推迟一个提取周期，可接受。
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "rag.memory", name = "type", havingValue = "jdbc")
public class MemoryExtractionService {

    private static final String EXTRACT_INSTRUCTION = """
            你是对话记忆提取助手。基于对话内容提取以下两类信息：
            1. profileUpdates：长期有效的用户画像键值对（称呼/所在地、语言偏好、技术栈、明确约束等）；
            2. longTermMemories：值得跨会话保留的结论性事实（用户明确做过的决定、项目进展、确认过的结果等）。
            忽略寒暄、临时性提问、通用常识与用户已明确否认的信息，不得虚构。无内容可提取时输出空数组。
            仅输出 JSON，不要输出任何其他文字，格式：
            {"profileUpdates":[{"key":"画像项名称","value":"画像项内容"}],"longTermMemories":["一条结论性事实"]}
            """;

    /** 单条长期记忆的最大保留字符数，防超长内容导致向量膨胀 */
    private static final int MAX_MEMORY_CHARS = 200;

    private final ConversationMemory conversationMemory;
    private final EntityMemoryService entityMemoryService;
    private final LongTermMemoryService longTermMemoryService;
    private final MemoryLlmClient memoryLlmClient;
    private final MemoryProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 当前提取周期内已累计的完成轮数（进程内状态，重启归零） */
    private final ConcurrentHashMap<String, Integer> turnCounters = new ConcurrentHashMap<>();

    /** 脏会话标记：自上次检查后有新完成轮数、待扫描判定 */
    private final Set<String> dirtySessions = ConcurrentHashMap.newKeySet();

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ExtractResult(List<Map<String, String>> profileUpdates, List<String> longTermMemories) {
    }

    public MemoryExtractionService(ConversationMemory conversationMemory,
                                   EntityMemoryService entityMemoryService,
                                   LongTermMemoryService longTermMemoryService,
                                   MemoryLlmClient memoryLlmClient,
                                   MemoryProperties properties) {
        this.conversationMemory = conversationMemory;
        this.entityMemoryService = entityMemoryService;
        this.longTermMemoryService = longTermMemoryService;
        this.memoryLlmClient = memoryLlmClient;
        this.properties = properties;
    }

    /**
     * 请求侧调用（每轮回答完成）：计数 + 脏标记，O(1) 且永不抛异常。
     */
    public void onTurnCompleted(long userId, String sessionId) {
        try {
            if (!properties.getExtract().isEnabled() || properties.getExtract().getIntervalTurns() <= 0) {
                return;
            }
            String key = sessionKey(userId, sessionId);
            turnCounters.merge(key, 1, Integer::sum);
            dirtySessions.add(key);
        } catch (Exception e) {
            log.warn("记忆轮数记账失败: userId={}, sessionId={}", userId, sessionId, e);
        }
    }

    /** 会话清除联动：清零计数并移除脏标记，避免复用 sessionId 时残留旧轮数 */
    public void reset(long userId, String sessionId) {
        String key = sessionKey(userId, sessionId);
        turnCounters.remove(key);
        dirtySessions.remove(key);
    }

    /**
     * 周期扫描（默认 30s）：逐个消费脏标记并判定是否到达提取周期；
     * 每个会话独立 try-catch，单会话失败不影响其他会话（N1）。
     */
    @Scheduled(fixedDelayString = "${rag.memory.extract.scan-interval-ms:30000}")
    public void extractDueSessions() {
        if (dirtySessions.isEmpty()) {
            return;
        }
        for (String key : List.copyOf(dirtySessions)) {
            if (!dirtySessions.remove(key)) {
                continue;
            }
            try {
                extractIfDue(key);
            } catch (Exception e) {
                log.warn("会话记忆提取失败，跳过本会话: key={}", key, e);
            }
        }
    }

    /**
     * 单会话提取判定与执行：到达周期后取「累计轮数+1 轮重叠」的原文窗口喂给小模型，
     * 重叠 1 轮用于兜住重启丢计数导致的边界遗漏；未到周期不动计数。
     * 包内可见便于单测直接驱动。
     */
    void extractIfDue(String key) {
        int interval = properties.getExtract().getIntervalTurns();
        if (interval <= 0) {
            return;
        }
        Integer current = turnCounters.get(key);
        if (current == null || current < interval) {
            return;
        }
        int turns = captureAndResetTurns(key);
        if (turns < interval) {
            // 与并发 onTurnCompleted 的竞态窗口内轮数被预先取走：本轮放弃，剩余轮数重新累计
            return;
        }
        long userId = parseUserId(key);
        String sessionId = parseSessionId(key);
        List<ChatMessage> window = conversationMemory.load(userId, sessionId, (turns + 1) * 2);
        if (window == null || window.isEmpty()) {
            return;
        }
        ExtractResult result = extractByLlm(window);
        if (result == null) {
            return;
        }
        int profileCount = 0;
        if (result.profileUpdates() != null) {
            entityMemoryService.applyUpdates(userId, result.profileUpdates());
            profileCount = result.profileUpdates().size();
        }
        int memoryCount = 0;
        if (result.longTermMemories() != null) {
            for (String memory : result.longTermMemories()) {
                if (memory != null && !memory.isBlank()) {
                    longTermMemoryService.save(userId, truncate(memory), sessionId);
                    memoryCount++;
                }
            }
        }
        log.info("记忆提取写回完成: userId={}, sessionId={}, turns={}, profileUpdates={}, longTermMemories={}",
                userId, sessionId, turns, profileCount, memoryCount);
    }

    /** 小模型提取 + JSON 解析；调用失败/解析失败返回 null（本轮跳过，N1） */
    private ExtractResult extractByLlm(List<ChatMessage> window) {
        try {
            StringBuilder dialogue = new StringBuilder();
            for (ChatMessage m : window) {
                if ("system".equals(m.role())) {
                    continue; // 摘要前缀等系统消息不作为提取素材
                }
                dialogue.append(m.role()).append("：").append(m.content()).append('\n');
            }
            String raw = memoryLlmClient.chat(List.of(
                    ChatMessage.system(EXTRACT_INSTRUCTION),
                    ChatMessage.user("对话内容：\n" + dialogue)));
            return objectMapper.readValue(stripCodeFence(raw), ExtractResult.class);
        } catch (Exception e) {
            log.warn("记忆提取小模型调用/解析失败，跳过本轮写回", e);
            return null;
        }
    }

    /** 容错处理小模型常见的 ```json 代码块包裹 */
    private String stripCodeFence(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.startsWith("```")) {
            s = s.replaceFirst("^```[a-zA-Z]*\\s*", "");
            int fenceEnd = s.lastIndexOf("```");
            if (fenceEnd >= 0) {
                s = s.substring(0, fenceEnd);
            }
        }
        return s.trim();
    }

    private String truncate(String memory) {
        return memory.length() <= MAX_MEMORY_CHARS ? memory : memory.substring(0, MAX_MEMORY_CHARS);
    }

    /** 原子取走并清零计数：提取进行中的新增轮数会累计到新周期 */
    private int captureAndResetTurns(String key) {
        int[] captured = new int[1];
        turnCounters.compute(key, (k, v) -> {
            captured[0] = v == null ? 0 : v;
            return 0;
        });
        return captured[0];
    }

    private static String sessionKey(long userId, String sessionId) {
        return userId + ":" + sessionId;
    }

    private static long parseUserId(String key) {
        return Long.parseLong(key.substring(0, key.indexOf(':')));
    }

    private static String parseSessionId(String key) {
        return key.substring(key.indexOf(':') + 1);
    }
}

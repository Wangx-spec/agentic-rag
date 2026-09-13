package com.agenticrag.memory;

import com.agenticrag.llm.dto.ChatMessage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 会话记忆（内存实现）：每个会话保留最近 N 轮消息
 */
@Component
@ConditionalOnProperty(prefix = "rag.memory", name = "type", havingValue = "memory", matchIfMissing = true)
public class InMemoryConversationMemory implements ConversationMemory{

    private final Map<String, ConcurrentLinkedDeque<ChatMessage>> sessions = new ConcurrentHashMap<>();

    /**
     * 加载会话历史消息
     */
    @Override 
    public List<ChatMessage> load(String sessionId, int maxMessages) {
        ConcurrentLinkedDeque<ChatMessage> history = sessions.get(sessionId);
        if (history == null) {
            return List.of();
        }  

        List<ChatMessage> all = new ArrayList<>(history);
        if (all.size() <= maxMessages) {
            return all;
        }
        return all.subList(all.size() - maxMessages, all.size());
    }

    /**
     * 追加会话消息
     */
    @Override
    public void append(String sessionId, ChatMessage message) {
        ConcurrentLinkedDeque<ChatMessage> history =
                sessions.computeIfAbsent(sessionId, k -> new ConcurrentLinkedDeque<>());
        history.addLast(message);
    }
    
    /**
     * 清除会话历史消息
     */
    @Override
    public void clear(String sessionId) {
        sessions.remove(sessionId);
    }

}

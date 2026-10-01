package com.agenticrag.memory;

import com.agenticrag.llm.dto.ChatMessage;


import java.util.List;

/**
 * 会话记忆接口
 */
public interface ConversationMemory {
    List<ChatMessage> load(long userId, String sessionId, int maxMessages);

    void append(long userId, String sessionId, ChatMessage message);

    void clear(long userId, String sessionId);
}

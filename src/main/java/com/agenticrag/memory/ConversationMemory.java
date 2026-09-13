package com.agenticrag.memory;

import com.agenticrag.llm.dto.ChatMessage;


import java.util.List;


public interface ConversationMemory {
    List<ChatMessage> load(String sessionId, int maxMessages);

    void append(String sessionId, ChatMessage message);

    void clear(String sessionId);
}

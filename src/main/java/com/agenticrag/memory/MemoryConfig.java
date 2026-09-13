package com.agenticrag.memory;

import com.agenticrag.memory.ConversationMemory;
import com.agenticrag.memory.InMemoryConversationMemory;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * 记忆装配配置：按 rag.memory.type 选择实现。
 * <p>
 */
@Configuration
@Import(InMemoryConversationMemory.class)
public class MemoryConfig {
    
}

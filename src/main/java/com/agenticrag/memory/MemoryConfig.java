package com.agenticrag.memory;

import com.agenticrag.memory.ConversationMemory;
import com.agenticrag.memory.InMemoryConversationMemory;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * 记忆装配配置：按 rag.memory.type 三态选择实现。
 * <p>
 * memory → InMemoryConversationMemory
 * redis  → RedisConversationMemory
 * jdbc   → JdbcConversationMemory
 * 三个实现均通过 @Component + @ConditionalOnProperty 自装配；
 * memory/redis 模式下实体/长期记忆降级为空实现
 */
@Configuration
@Import(InMemoryConversationMemory.class)
public class MemoryConfig {
    
}

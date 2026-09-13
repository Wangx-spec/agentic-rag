package com.agenticrag.memory;

import com.agenticrag.llm.dto.ChatMessage;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;


@Component
@ConditionalOnProperty(prefix = "rag.memory", name = "type", havingValue = "redis")
@RequiredArgsConstructor
@Slf4j
public class RedisConversationMemory implements ConversationMemory {

    private static final String KEY_PREFIX = "chat:memory:";
    private static final Duration TTL = Duration.ofDays(7);
    private static final TypeReference<List<ChatMessage>> CHAT_MESSAGE_LIST = new TypeReference<>() {};

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Override 
    public List<ChatMessage> load(String sessionId, int maxMessages) {
        if (maxMessages <= 0){
            return List.of();
        }

        List<ChatMessage> all = readMessages(sessionId);
        if (all.size() <= maxMessages) {
            return all;
        }

        return new ArrayList<>(all.subList(all.size() - maxMessages, all.size()));
    }

    @Override 
    public void append(String sessionId, ChatMessage message) {
        List<ChatMessage> all = new ArrayList<>(readMessages(sessionId));
        all.add(message);
        writeMessages(sessionId, all);
    }

    @Override
    public void clear(String sessionId) {
        redisTemplate.delete(buildKey(sessionId));  
    }

    private List<ChatMessage> readMessages(String sessionId) {
        String json = redisTemplate.opsForValue().get(buildKey(sessionId));
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<ChatMessage> messages = objectMapper.readValue(json, CHAT_MESSAGE_LIST);;
            return messages == null ? List.of() : messages;
        } catch (Exception e) {
            log.error("读取 Redis 会话记忆失败，sessionId={}", sessionId, e);
            return List.of();
        }
    }

    private void writeMessages(String sessionId, List<ChatMessage> messages) {
        try {
            String json = objectMapper.writeValueAsString(messages);
            redisTemplate.opsForValue().set(buildKey(sessionId), json, TTL);
        } catch (Exception e) {
            log.error("写入 Redis 会话记忆失败，sessionId={}", sessionId, e);
        }
    }

    private String buildKey(String sessionId) {
        return KEY_PREFIX + sessionId;
    }
}

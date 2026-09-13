package com.agenticrag.memory;

import com.agenticrag.llm.dto.ChatMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisConversationMemoryTest {

    private static final String SESSION_ID = "s1";
    private static final String KEY = "chat:memory:s1";

    private final ObjectMapper objectMapper = new ObjectMapper();

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private RedisConversationMemory memory;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        memory = new RedisConversationMemory(redisTemplate, objectMapper);
    }

    @Test
    void appendStoresJsonAndRefreshesTtl() throws Exception {
        when(valueOperations.get(KEY)).thenReturn(objectMapper.writeValueAsString(List.of(
                ChatMessage.user("你好")
        )));

        memory.append(SESSION_ID, ChatMessage.assistant("你好，有什么可以帮你？"));

        String expectedJson = objectMapper.writeValueAsString(List.of(
                ChatMessage.user("你好"),
                ChatMessage.assistant("你好，有什么可以帮你？")
        ));
        verify(valueOperations).set(KEY, expectedJson, Duration.ofDays(7));
    }

    @Test
    void loadReturnsLatestMessagesOnly() throws Exception {
        when(valueOperations.get(KEY)).thenReturn(objectMapper.writeValueAsString(List.of(
                ChatMessage.user("1"),
                ChatMessage.assistant("2"),
                ChatMessage.user("3")
        )));

        List<ChatMessage> loaded = memory.load(SESSION_ID, 2);

        assertEquals(2, loaded.size());
        assertEquals("assistant", loaded.get(0).role());
        assertEquals("3", loaded.get(1).content());
    }

    @Test
    void loadReturnsEmptyWhenJsonIsMalformed() {
        when(valueOperations.get(KEY)).thenReturn("{bad json");

        List<ChatMessage> loaded = memory.load(SESSION_ID, 10);

        assertTrue(loaded.isEmpty());
    }

    @Test
    void clearDeletesRedisKey() {
        memory.clear(SESSION_ID);

        verify(redisTemplate).delete(KEY);
    }

    @Test
    void loadReturnsEmptyWhenMaxMessagesIsNonPositive() {
        List<ChatMessage> loaded = memory.load(SESSION_ID, 0);

        assertTrue(loaded.isEmpty());
        verify(valueOperations, never()).get(anyString());
    }
}

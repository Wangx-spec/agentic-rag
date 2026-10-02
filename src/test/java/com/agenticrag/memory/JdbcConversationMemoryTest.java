package com.agenticrag.memory;

import com.agenticrag.llm.dto.ChatMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * JdbcConversationMemory 单测（mock JdbcTemplate 验证 SQL 语义与参数顺序，T4 验证条款）。
 */
class JdbcConversationMemoryTest {

    private JdbcTemplate jdbcTemplate;
    private JdbcConversationMemory memory;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        memory = new JdbcConversationMemory(jdbcTemplate);
    }

    @Test
    @SuppressWarnings("unchecked")
    void load按id倒序取最近N条后反转为时间正序() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(7L), eq("s1"), eq(4)))
                .thenReturn(List.of(ChatMessage.assistant("新消息"), ChatMessage.user("旧消息")));

        List<ChatMessage> loaded = memory.load(7L, "s1", 4);

        assertEquals(2, loaded.size());
        assertEquals("旧消息", loaded.get(0).content());
        assertEquals("新消息", loaded.get(1).content());
    }

    @Test
    @SuppressWarnings("unchecked")
    void load带用户与会话过滤条件() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), anyLong(), anyString(), anyInt()))
                .thenReturn(List.of());

        memory.load(42L, "sess-x", 10);

        verify(jdbcTemplate).query(contains("WHERE user_id = ? AND session_id = ?"),
                any(RowMapper.class), eq(42L), eq("sess-x"), eq(10));
    }

    @Test
    @SuppressWarnings("unchecked")
    void load异常时按空历史降级不抛错() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), anyLong(), anyString(), anyInt()))
                .thenThrow(new RuntimeException("db down"));

        assertTrue(memory.load(0L, "s1", 4).isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    void load非正窗口直接返回空且不查库() {
        assertTrue(memory.load(0L, "s1", 0).isEmpty());
        verify(jdbcTemplate, never()).query(anyString(), any(RowMapper.class), anyLong(), anyString(), anyInt());
    }

    @Test
    void append写入用户会话与消息字段() {
        memory.append(3L, "s1", ChatMessage.user("你好"));

        verify(jdbcTemplate).update(contains("INSERT INTO conversation_messages"),
                eq(3L), eq("s1"), eq("user"), eq("你好"));
    }

    @Test
    void append异常仅记日志不抛出() {
        doThrow(new RuntimeException("db down")).when(jdbcTemplate)
                .update(anyString(), anyLong(), anyString(), anyString(), anyString());

        assertDoesNotThrow(() -> memory.append(0L, "s1", ChatMessage.user("x")));
    }

    @Test
    void clear按用户与会话删除() {
        memory.clear(9L, "s1");

        verify(jdbcTemplate).update(contains("DELETE FROM conversation_messages"), eq(9L), eq("s1"));
    }
}
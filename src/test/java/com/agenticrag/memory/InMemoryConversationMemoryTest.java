package com.agenticrag.memory;

import com.agenticrag.llm.dto.ChatMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * InMemoryConversationMemory 单测（T3 验证条款：多 userId 同 sessionId 互不串扰）。
 */
class InMemoryConversationMemoryTest {

    private final InMemoryConversationMemory memory = new InMemoryConversationMemory();

    @Test
    void 同sessionId不同userId互不串扰() {
        memory.append(1L, "s1", ChatMessage.user("用户1的消息"));
        memory.append(2L, "s1", ChatMessage.user("用户2的消息"));

        List<ChatMessage> u1 = memory.load(1L, "s1", 10);
        List<ChatMessage> u2 = memory.load(2L, "s1", 10);

        assertEquals(1, u1.size());
        assertEquals("用户1的消息", u1.get(0).content());
        assertEquals(1, u2.size());
        assertEquals("用户2的消息", u2.get(0).content());
    }

    @Test
    void 窗口截取返回最近N条且保持时间正序() {
        for (int i = 1; i <= 5; i++) {
            memory.append(0L, "s1", ChatMessage.user("m" + i));
        }

        List<ChatMessage> loaded = memory.load(0L, "s1", 2);

        assertEquals(2, loaded.size());
        assertEquals("m4", loaded.get(0).content());
        assertEquals("m5", loaded.get(1).content());
    }

    @Test
    void clear仅清除目标用户的目标会话() {
        memory.append(1L, "s1", ChatMessage.user("u1-s1"));
        memory.append(1L, "s2", ChatMessage.user("u1-s2"));
        memory.append(2L, "s1", ChatMessage.user("u2-s1"));

        memory.clear(1L, "s1");

        assertTrue(memory.load(1L, "s1", 10).isEmpty());
        assertEquals(1, memory.load(1L, "s2", 10).size());
        assertEquals(1, memory.load(2L, "s1", 10).size());
    }
}

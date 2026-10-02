package com.agenticrag.memory;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * MemoryScope 单测（M9/T9）：验证 ThreadLocal 上下文的 set/clear 生命周期与数据正确性。
 */
class MemoryScopeTest {

    @AfterEach
    void tearDown() {
        MemoryScope.clear();
    }

    @Test
    void set后current返回完整上下文() {
        MemoryScope.set(7L, "s1");
        MemoryScope.Context ctx = MemoryScope.current();
        assertEquals(7L, ctx.userId());
        assertEquals("s1", ctx.sessionId());
    }

    @Test
    void clear后current返回null() {
        MemoryScope.set(7L, "s1");
        MemoryScope.clear();
        assertNull(MemoryScope.current());
    }

    @Test
    void 未设置时current返回null() {
        assertNull(MemoryScope.current());
    }

    @Test
    void 二次set覆盖前值() {
        MemoryScope.set(1L, "a");
        MemoryScope.set(2L, "b");
        MemoryScope.Context ctx = MemoryScope.current();
        assertEquals(2L, ctx.userId());
        assertEquals("b", ctx.sessionId());
    }
}

package com.agenticrag.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolCallIdsTest {

    @Test
    void generatesStableIdWhenBlank() {
        String id = ToolCallIds.normalize("  ", "run_sql", 0);

        assertTrue(id.startsWith("call_"));
        assertEquals(id, ToolCallIds.normalize(null, "run_sql", 0));
        assertNotEquals(id, ToolCallIds.normalize(null, "run_sql", 1));
    }

    @Test
    void truncatesLongIdsWithHashSuffix() {
        String id = ToolCallIds.normalize("x".repeat(90), "run_sql", 0);

        assertTrue(id.length() <= 64);
        assertTrue(id.startsWith("x".repeat(48) + "_"));
    }

    @Test
    void replacesDirtyCharacters() {
        String id = ToolCallIds.normalize(" call:中文/id ", "run_sql", 0);

        assertEquals("call____id", id);
    }
}

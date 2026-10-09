package com.agenticrag.tool;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolCallJsonRepairTest {

    private final ToolCallJsonRepair repair = new ToolCallJsonRepair();

    @Test
    void validJsonPassesThroughUnchanged() {
        String json = "{\"query\":\"M3 状态机\"}";

        ToolCallJsonRepair.RepairResult result = repair.repair(json);

        assertEquals(json, result.repairedJson());
        assertFalse(result.truncated());
    }

    @Test
    void removesTrailingCommas() {
        ToolCallJsonRepair.RepairResult result = repair.repair("{\"query\":\"M3\",}");

        assertEquals("{\"query\":\"M3\"}", result.repairedJson());
        assertFalse(result.truncated());
    }

    @Test
    void repairsSingleQuotedKeysAndValues() {
        ToolCallJsonRepair.RepairResult result = repair.repair("{'query':'M3 状态机'}");

        assertEquals("{\"query\":\"M3 状态机\"}", result.repairedJson());
        assertFalse(result.truncated());
    }

    @Test
    void marksUnclosedArgumentsAsTruncated() {
        ToolCallJsonRepair.RepairResult result = repair.repair("{\"query\":\"M3 状态机");

        assertTrue(result.truncated());
        assertTrue(result.repairedJson().endsWith("\"}"));
    }

    @Test
    void keepsFirstTopLevelObjectOnly() {
        ToolCallJsonRepair.RepairResult result = repair.repair("{\"query\":\"a\"}{\"query\":\"b\"}");

        assertEquals("{\"query\":\"a\"}", result.repairedJson());
        assertFalse(result.truncated());
    }

    @Test
    void wrapsKeyValueFormIntoObject() {
        ToolCallJsonRepair.RepairResult result = repair.repair("query=M3, topK=3");

        assertEquals("{\"query\":\"M3\",\"topK\":3}", result.repairedJson());
        assertFalse(result.truncated());
    }
}

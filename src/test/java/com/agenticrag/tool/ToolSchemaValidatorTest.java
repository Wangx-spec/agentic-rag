package com.agenticrag.tool;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolSchemaValidatorTest {

    private final ToolSchemaValidator validator = new ToolSchemaValidator();

    @Test
    void validateReturnsReadableMessageWhenRequiredFieldMissing() {
        String schema = """
                {
                  "type": "object",
                  "properties": {
                    "query": { "type": "string" }
                  },
                  "required": ["query"]
                }
                """;

        ToolSchemaValidator.ValidationResult result = validator.validate(schema, Map.of());

        assertFalse(result.valid());
        assertEquals("缺失参数: query", result.message());
    }

    @Test
    void validateReturnsReadableMessageWhenTypeMismatch() {
        String schema = """
                {
                  "type": "object",
                  "properties": {
                    "query": { "type": "string" }
                  },
                  "required": ["query"]
                }
                """;

        ToolSchemaValidator.ValidationResult result = validator.validate(schema, Map.of("query", 123));

        assertFalse(result.valid());
        assertEquals("参数 query 类型错误，期望 string，实际为 integer", result.message());
    }

    @Test
    void validatePassesWhenArgumentsMatchSchema() {
        String schema = """
                {
                  "type": "object",
                  "properties": {
                    "query": { "type": "string" },
                    "topK": { "type": "integer" }
                  },
                  "required": ["query"]
                }
                """;

        ToolSchemaValidator.ValidationResult result = validator.validate(
                schema,
                Map.of("query", "M3 状态机", "topK", 5)
        );

        assertTrue(result.valid());
        assertEquals("", result.message());
    }

    @Test
    void castConvertsCompatibleStringValuesBeforeValidation() {
        String schema = """
                {
                  "type": "object",
                  "properties": {
                    "topK": { "type": "integer" },
                    "enabled": { "type": "boolean" },
                    "ids": { "type": "array" },
                    "query": { "type": "string" }
                  }
                }
                """;

        Map<String, Object> casted = validator.cast(schema,
                Map.of("topK", "3", "enabled", "true", "ids", "[1,2]", "query", 99));

        assertEquals(3L, casted.get("topK"));
        assertEquals(true, casted.get("enabled"));
        assertEquals(List.of(1, 2), casted.get("ids"));
        assertEquals("99", casted.get("query"));
        assertTrue(validator.validate(schema, casted).valid());
    }

    @Test
    void castLeavesUnconvertibleValuesForValidator() {
        String schema = """
                {
                  "type": "object",
                  "properties": {
                    "topK": { "type": "integer" }
                  }
                }
                """;

        Map<String, Object> casted = validator.cast(schema, Map.of("topK", "abc"));

        assertEquals("abc", casted.get("topK"));
        assertFalse(validator.validate(schema, casted).valid());
    }
}

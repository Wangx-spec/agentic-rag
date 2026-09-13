package com.agenticrag.tool;

import org.junit.jupiter.api.Test;

import java.util.Map;

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
}
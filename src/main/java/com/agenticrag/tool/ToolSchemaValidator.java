package com.agenticrag.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.List;
import java.util.Map;

@Component
public class ToolSchemaValidator {
    
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 校验工具参数是否符合工具 schema 规范
     * @param schemaJson 工具 schema 字符串
     * @param arguments 工具参数
     * @return 校验结果
     */
    public ValidationResult validate(String schemaJson, Map<String, Object> arguments) {
        if (schemaJson == null || schemaJson.isBlank()) {
            return ValidationResult.ok();
        }
        try {
            JsonNode schema = objectMapper.readTree(schemaJson);
            JsonNode requiredNode = schema.path("required");
            if (requiredNode.isArray()) {
                for (JsonNode item : requiredNode) {
                    String field = item.asText();
                    if (!arguments.containsKey(field) || arguments.get(field) == null) {
                        return ValidationResult.error("缺失参数: " + field);
                    }
                }
            }

            JsonNode propertiesNode = schema.path("properties");
            if (propertiesNode.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> fields = propertiesNode.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> entry = fields.next();
                    String fieldName = entry.getKey();
                    if (!arguments.containsKey(fieldName) || arguments.get(fieldName) == null) {
                        continue;
                    }
                    String expectedType = entry.getValue().path("type").asText("");
                    if (expectedType.isBlank()) {
                        continue;
                    }
                    Object value = arguments.get(fieldName);
                    if (!matchesType(value, expectedType)) {
                        return ValidationResult.error(
                                "参数 " + fieldName + " 类型错误，期望 " + expectedType + "，实际为 " + actualType(value)
                        );
                    }
                }
            }
            return ValidationResult.ok();
        } catch (Exception e) {
            return ValidationResult.error("工具参数校验失败: schema 非法 - " + e.getMessage());
        }
    }

    /**
     * 校验工具参数值是否符合工具 schema 规范
     * @param value 工具参数值
     * @param expectedType 工具 schema 中期望的参数类型
     * @return 是否符合工具 schema 规范
     */
    private boolean matchesType(Object value, String expectedType) {
        return switch (expectedType) {
            case "string" -> value instanceof String;
            case "integer" -> value instanceof Byte
                    || value instanceof Short
                    || value instanceof Integer
                    || value instanceof Long;
            case "number" -> value instanceof Number;
            case "boolean" -> value instanceof Boolean;
            case "object" -> value instanceof Map<?, ?>;
            case "array" -> value instanceof List<?>;
            default -> true;
        };
    }

    /**
     * 获取工具参数值的实际类型
     * @param value 工具参数值
     * @return 工具参数值的实际类型
     */
    private String actualType(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String) {
            return "string";
        }
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            return "integer";
        }
        if (value instanceof Number) {
            return "number";
        }
        if (value instanceof Boolean) {
            return "boolean";
        }
        if (value instanceof Map<?, ?>) {
            return "object";
        }
        if (value instanceof List<?>) {
            return "array";
        }
        return value.getClass().getSimpleName();
    }

    /**
     * 工具参数校验结果
     * @param valid 是否符合工具 schema 规范
     * @param message 校验结果消息
     */
    public record ValidationResult(boolean valid, String message) {
        public static ValidationResult ok() {
            return new ValidationResult(true, "");
        }

        public static ValidationResult error(String message) {
            return new ValidationResult(false, message);
        }
    }

}

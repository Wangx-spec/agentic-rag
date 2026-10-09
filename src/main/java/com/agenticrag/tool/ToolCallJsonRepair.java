package com.agenticrag.tool;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * Best-effort repair for malformed tool-call argument JSON emitted by LLMs.
 * Truncated inputs are marked so callers can refuse execution.
 */
public class ToolCallJsonRepair {

    private final ObjectMapper objectMapper = new ObjectMapper();

    public RepairResult repair(String json) {
        if (json == null) {
            return new RepairResult(null, false);
        }
        if (isValid(json)) {
            return new RepairResult(json, false);
        }

        String repaired = json.trim();
        boolean truncated = hasUnclosedQuoteOrBracket(repaired);
        repaired = keyValueToObjectIfNeeded(repaired);
        repaired = replaceSingleQuotedStrings(repaired);
        repaired = removeTrailingCommas(repaired);
        repaired = escapeInvalidBackslashes(repaired);
        repaired = truncateAfterFirstTopLevelValue(repaired);
        repaired = closeUnbalanced(repaired);
        return new RepairResult(repaired, truncated);
    }

    private boolean isValid(String json) {
        try (JsonParser parser = objectMapper.getFactory().createParser(json)) {
            JsonNode ignored = objectMapper.readTree(parser);
            return ignored != null && parser.nextToken() == null;
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean hasUnclosedQuoteOrBracket(String value) {
        boolean inString = false;
        boolean escaped = false;
        int braces = 0;
        int brackets = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                braces++;
            } else if (c == '}') {
                braces = Math.max(0, braces - 1);
            } else if (c == '[') {
                brackets++;
            } else if (c == ']') {
                brackets = Math.max(0, brackets - 1);
            }
        }
        return inString || braces > 0 || brackets > 0;
    }

    private String keyValueToObjectIfNeeded(String value) {
        String trimmed = value.trim();
        if (trimmed.startsWith("{") || trimmed.startsWith("[") || !trimmed.contains("=")) {
            return value;
        }
        List<String> fields = splitTopLevel(trimmed, ',');
        List<String> jsonFields = new ArrayList<>();
        for (String field : fields) {
            int idx = field.indexOf('=');
            if (idx <= 0) {
                return value;
            }
            String key = quote(field.substring(0, idx).trim());
            String rawValue = field.substring(idx + 1).trim();
            jsonFields.add(key + ":" + renderValue(rawValue));
        }
        return "{" + String.join(",", jsonFields) + "}";
    }

    private List<String> splitTopLevel(String value, char delimiter) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inString = false;
        char quote = 0;
        int depth = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (inString) {
                current.append(c);
                if (c == quote && (i == 0 || value.charAt(i - 1) != '\\')) {
                    inString = false;
                }
                continue;
            }
            if (c == '\'' || c == '"') {
                inString = true;
                quote = c;
                current.append(c);
            } else if (c == '{' || c == '[') {
                depth++;
                current.append(c);
            } else if (c == '}' || c == ']') {
                depth = Math.max(0, depth - 1);
                current.append(c);
            } else if (c == delimiter && depth == 0) {
                result.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        result.add(current.toString());
        return result;
    }

    private String renderValue(String value) {
        if (value.isBlank()) {
            return "\"\"";
        }
        String lower = value.toLowerCase();
        if (value.startsWith("{") || value.startsWith("[") || value.startsWith("\"") || value.startsWith("'")
                || "true".equals(lower) || "false".equals(lower) || "null".equals(lower)
                || value.matches("-?\\d+(\\.\\d+)?")) {
            return replaceSingleQuotedStrings(value);
        }
        return quote(value);
    }

    private String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private String replaceSingleQuotedStrings(String value) {
        StringBuilder out = new StringBuilder(value.length());
        boolean inSingle = false;
        boolean inDouble = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"' && !inSingle) {
                inDouble = !inDouble;
                out.append(c);
            } else if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
                out.append('"');
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private String removeTrailingCommas(String value) {
        return value.replaceAll(",\\s*([}\\]])", "$1");
    }

    private String escapeInvalidBackslashes(String value) {
        StringBuilder out = new StringBuilder(value.length());
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!inString) {
                if (c == '"') {
                    inString = true;
                }
                out.append(c);
                continue;
            }
            if (escaped) {
                if ("\"\\/bfnrtu".indexOf(c) < 0) {
                    out.append('\\');
                }
                out.append(c);
                escaped = false;
            } else if (c == '\\') {
                out.append(c);
                escaped = true;
            } else {
                if (c == '"') {
                    inString = false;
                }
                out.append(c);
            }
        }
        if (escaped) {
            out.append('\\');
        }
        return out.toString();
    }

    private String truncateAfterFirstTopLevelValue(String value) {
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return trimmed;
        }
        char first = trimmed.charAt(0);
        if (first != '{' && first != '[') {
            return trimmed;
        }
        boolean inString = false;
        boolean escaped = false;
        int depth = 0;
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{' || c == '[') {
                depth++;
            } else if (c == '}' || c == ']') {
                depth--;
                if (depth == 0) {
                    return trimmed.substring(0, i + 1);
                }
            }
        }
        return trimmed;
    }

    private String closeUnbalanced(String value) {
        StringBuilder out = new StringBuilder(value);
        boolean inString = false;
        boolean escaped = false;
        List<Character> stack = new ArrayList<>();
        for (int i = 0; i < out.length(); i++) {
            char c = out.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                stack.add('}');
            } else if (c == '[') {
                stack.add(']');
            } else if ((c == '}' || c == ']') && !stack.isEmpty()) {
                stack.remove(stack.size() - 1);
            }
        }
        if (inString) {
            out.append('"');
        }
        for (int i = stack.size() - 1; i >= 0; i--) {
            out.append(stack.get(i));
        }
        return out.toString();
    }

    public record RepairResult(String repairedJson, boolean truncated) {}
}

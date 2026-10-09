package com.agenticrag.dataanalysis;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SQL 只读安全校验（N1 应用层，与数据库只读账号双层兜底）。
 * 先剥离字符串字面量/双引号标识符/行注释/块注释（避免误杀），再校验：
 * ① 前缀仅允许 SELECT/WITH；② 允许末尾一个分号、拒绝中间分号（多语句）；③ 拒绝危险关键字。
 */
@Component
public class SqlSafetyGuard {

    private static final Pattern TABLE_REFERENCE_PATTERN =
            Pattern.compile("(?i)\\b(?:FROM|JOIN)\\s+([a-zA-Z_][\\w.]*)(?!\\s*\\()");
    private static final Pattern CTE_NAME_PATTERN =
            Pattern.compile("(?i)(?:WITH|,)\\s+([a-zA-Z_][\\w]*)\\s*(?:\\([^)]*\\)\\s*)?AS\\s*\\(");

    private static final Set<String> DANGEROUS_KEYWORDS = Set.of(
            "INSERT", "UPDATE", "DELETE", "DROP", "ALTER", "CREATE", "TRUNCATE",
            "COPY", "INTO", "GRANT", "REVOKE", "MERGE", "REPLACE", "CALL",
            "EXEC", "EXECUTE", "PG_READ_FILE", "PG_LS_DIR", "PG_SLEEP",
            "LOCK", "VACUUM", "ANALYZE", "REINDEX", "CLUSTER", "REFRESH",
            "SET", "RESET", "SHOW", "LISTEN", "NOTIFY", "UNLISTEN",
            "BEGIN", "COMMIT", "ROLLBACK", "SAVEPOINT", "RELEASE",
            "PREPARE", "DEALLOCATE", "SECURITY", "COMMENT", "DISCARD", "LOAD"
    );

    private DemoSchemaService demoSchemaService;

    public SqlSafetyGuard() {
    }

    public SqlSafetyGuard(DemoSchemaService demoSchemaService) {
        this.demoSchemaService = demoSchemaService;
    }

    @Autowired(required = false)
    public void setDemoSchemaService(DemoSchemaService demoSchemaService) {
        this.demoSchemaService = demoSchemaService;
    }

    /**
     * 校验 SQL 是否只读安全。
     * @param sql 原始 SQL
     * @return 校验通过的原 SQL
     * @throws SqlSafetyException 校验不通过（信息已脱敏）
     */
    public String validate(String sql) {
        if (sql == null || sql.isBlank()) {
            throw new SqlSafetyException("SQL 不能为空");
        }
        String stripped = stripLiteralsAndComments(sql);
        String trimmed = stripped.trim();
        if (trimmed.isEmpty()) {
            throw new SqlSafetyException("SQL 不能为空");
        }

        String upper = trimmed.toUpperCase(Locale.ROOT);
        if (!upper.startsWith("SELECT") && !upper.startsWith("WITH")) {
            throw new SqlSafetyException("仅允许 SELECT 或 WITH 开头的只读查询");
        }

        checkSemicolon(trimmed);
        checkDangerousKeywords(trimmed);
        checkAllowedTables(trimmed);
        return sql;
    }

    /** 尾分号规则：允许末尾一个分号，拒绝中间分号（多语句注入）。 */
    private void checkSemicolon(String strippedSql) {
        String body = strippedSql.endsWith(";")
                ? strippedSql.substring(0, strippedSql.length() - 1)
                : strippedSql;
        if (body.indexOf(';') >= 0) {
            throw new SqlSafetyException("检测到多条 SQL 语句，仅允许单条查询");
        }
    }

    /** 在剥离后的 token 流中按词边界匹配危险关键字。 */
    private void checkDangerousKeywords(String strippedSql) {
        String upper = strippedSql.toUpperCase(Locale.ROOT);
        for (String keyword : DANGEROUS_KEYWORDS) {
            int idx = 0;
            while ((idx = upper.indexOf(keyword, idx)) >= 0) {
                boolean leftOk = idx == 0 || !isWordChar(upper.charAt(idx - 1));
                int end = idx + keyword.length();
                boolean rightOk = end >= upper.length() || !isWordChar(upper.charAt(end));
                if (leftOk && rightOk) {
                    throw new SqlSafetyException("检测到不允许的 SQL 操作，仅支持只读查询");
                }
                idx = end;
            }
        }
    }

    private void checkAllowedTables(String strippedSql) {
        if (demoSchemaService == null || demoSchemaService.tableNamesLowercase().isEmpty()) {
            return;
        }
        Set<String> referenced = extractReferencedTables(strippedSql);
        if (referenced.isEmpty()) {
            return;
        }
        Set<String> cteNames = extractCteNames(strippedSql);
        Set<String> allowed = demoSchemaService.tableNamesLowercase();
        for (String table : referenced) {
            if (cteNames.contains(table)) {
                continue;
            }
            if (!allowed.contains(table)) {
                throw new UnknownTableException("表 " + table + " 不存在，可用表：" + demoSchemaService.availableTableList());
            }
        }
    }

    private Set<String> extractReferencedTables(String strippedSql) {
        Set<String> tables = new LinkedHashSet<>();
        Matcher matcher = TABLE_REFERENCE_PATTERN.matcher(strippedSql);
        while (matcher.find()) {
            String token = matcher.group(1);
            String table = token.contains(".") ? token.substring(token.lastIndexOf('.') + 1) : token;
            if (!table.isBlank()) {
                tables.add(table.toLowerCase(Locale.ROOT));
            }
        }
        return tables;
    }

    private Set<String> extractCteNames(String strippedSql) {
        Set<String> names = new LinkedHashSet<>();
        Matcher matcher = CTE_NAME_PATTERN.matcher(strippedSql);
        while (matcher.find()) {
            names.add(matcher.group(1).toLowerCase(Locale.ROOT));
        }
        return names;
    }

    /**
     * 剥离字符串字面量（'...' 含 '' 转义）、双引号标识符（"..."）、行注释（--）、块注释（/星 ... 星/），
     * 被剥离内容替换为空格以保留位置信息（分号检测不受长度变化影响）。
     */
    private String stripLiteralsAndComments(String sql) {
        StringBuilder out = new StringBuilder(sql.length());
        int i = 0;
        int n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            if (c == '\'') {
                out.append(' ');
                i++;
                while (i < n) {
                    if (sql.charAt(i) == '\'') {
                        if (i + 1 < n && sql.charAt(i + 1) == '\'') {
                            i += 2;
                            continue;
                        }
                        i++;
                        break;
                    }
                    i++;
                }
            } else if (c == '"') {
                out.append(' ');
                i++;
                while (i < n) {
                    if (sql.charAt(i) == '"') {
                        i++;
                        break;
                    }
                    i++;
                }
            } else if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                while (i < n && sql.charAt(i) != '\n') {
                    i++;
                }
                out.append(' ');
            } else if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < n && !(sql.charAt(i) == '*' && sql.charAt(i + 1) == '/')) {
                    i++;
                }
                i = Math.min(i + 2, n);
                out.append(' ');
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    public static class UnknownTableException extends SqlSafetyException {
        public UnknownTableException(String message) {
            super(message);
        }
    }
}

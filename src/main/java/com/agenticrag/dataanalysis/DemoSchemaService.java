package com.agenticrag.dataanalysis;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "rag.data", name = "enabled", havingValue = "true")
public class DemoSchemaService {

    private static final String TABLES_SQL = """
            SELECT t.table_name, obj_description(c.oid) AS table_comment
            FROM information_schema.tables t
            JOIN pg_class c ON c.relname = t.table_name
            JOIN pg_namespace n ON n.oid = c.relnamespace AND n.nspname = t.table_schema
            WHERE t.table_schema = 'public' AND t.table_type = 'BASE TABLE'
            ORDER BY t.table_name
            """;

    private static final String COLUMNS_SQL = """
            SELECT column_name, data_type, col_description(
                (quote_ident(table_schema) || '.' || quote_ident(table_name))::regclass::oid,
                ordinal_position
            ) AS column_comment
            FROM information_schema.columns
            WHERE table_schema = 'public' AND table_name = ?
            ORDER BY ordinal_position
            """;

    private final JdbcTemplate demoJdbcTemplate;
    private volatile List<TableSchema> tables = List.of();

    public DemoSchemaService(@Qualifier("demoJdbcTemplate") JdbcTemplate demoJdbcTemplate) {
        this.demoJdbcTemplate = demoJdbcTemplate;
    }

    @PostConstruct
    public void refresh() {
        try {
            List<TableSchema> loaded = new ArrayList<>();
            for (Map<String, Object> row : demoJdbcTemplate.queryForList(TABLES_SQL)) {
                String tableName = String.valueOf(row.get("table_name"));
                String comment = row.get("table_comment") == null ? "" : String.valueOf(row.get("table_comment"));
                loaded.add(new TableSchema(tableName, comment, loadColumns(tableName), loadSamples(tableName)));
            }
            tables = List.copyOf(loaded);
            log.info("已加载 demo schema：{} 张表", tables.size());
        } catch (Exception e) {
            log.warn("加载 demo schema 失败，SQL 表名白名单降级放行", e);
            tables = List.of();
        }
    }

    public List<TableSchema> tables() {
        return tables;
    }

    public Set<String> tableNamesLowercase() {
        return tables.stream()
                .map(table -> table.name().toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    public String availableTableList() {
        if (tables.isEmpty()) {
            return "暂无可用表";
        }
        return tables.stream().map(TableSchema::name).collect(Collectors.joining(", "));
    }

    public String describeAll() {
        if (tables.isEmpty()) {
            return "当前数据库中没有表。";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("数据库共有 ").append(tables.size()).append(" 张表：\n");
        for (TableSchema table : tables) {
            appendTable(sb, table);
        }
        return sb.toString();
    }

    public String describeTable(String tableName) {
        return tables.stream()
                .filter(table -> table.name().equalsIgnoreCase(tableName))
                .findFirst()
                .map(table -> {
                    StringBuilder sb = new StringBuilder();
                    appendTable(sb, table);
                    return sb.toString();
                })
                .orElse("表 " + tableName + " 的字段：\n  （未找到该表或无字段）\n");
    }

    public String toolDescriptionSummary() {
        if (tables.isEmpty()) {
            return "可用业务表清单暂未加载，请先调用 list_tables 查看表结构。";
        }
        return tables.stream()
                .map(table -> table.name() + (table.comment().isBlank() ? "" : "（" + table.comment() + "）"))
                .collect(Collectors.joining("；"));
    }

    private List<ColumnSchema> loadColumns(String tableName) {
        List<ColumnSchema> columns = new ArrayList<>();
        for (Map<String, Object> row : demoJdbcTemplate.queryForList(COLUMNS_SQL, tableName)) {
            columns.add(new ColumnSchema(
                    String.valueOf(row.get("column_name")),
                    String.valueOf(row.get("data_type")),
                    row.get("column_comment") == null ? "" : String.valueOf(row.get("column_comment"))
            ));
        }
        return List.copyOf(columns);
    }

    private List<Map<String, Object>> loadSamples(String tableName) {
        try {
            String sql = "SELECT * FROM " + quoteIdentifier(tableName) + " LIMIT 2";
            List<Map<String, Object>> samples = new ArrayList<>();
            for (Map<String, Object> row : demoJdbcTemplate.queryForList(sql)) {
                samples.add(new LinkedHashMap<>(row));
            }
            return List.copyOf(samples);
        } catch (Exception e) {
            log.debug("读取 demo 表样本失败: table={}, err={}", tableName, e.getMessage());
            return List.of();
        }
    }

    private String quoteIdentifier(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    private void appendTable(StringBuilder sb, TableSchema table) {
        sb.append("\n## ").append(table.name());
        if (!table.comment().isBlank()) {
            sb.append("（").append(table.comment()).append("）");
        }
        sb.append("\n");
        if (table.columns().isEmpty()) {
            sb.append("  （未找到该表或无字段）\n");
        } else {
            sb.append("  列：");
            sb.append(table.columns().stream()
                    .map(column -> column.name() + "(" + column.type() + ")"
                            + (column.comment().isBlank() ? "" : ":" + column.comment()))
                    .collect(Collectors.joining(", ")));
            sb.append("\n");
        }
        if (table.samples().isEmpty()) {
            sb.append("  样本：无\n");
        } else {
            sb.append("  样本：").append(table.samples()).append("\n");
        }
    }

    public record TableSchema(String name, String comment, List<ColumnSchema> columns, List<Map<String, Object>> samples) {}

    public record ColumnSchema(String name, String type, String comment) {}
}

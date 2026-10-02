package com.agenticrag.tool.tools;

import com.agenticrag.tool.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ListTablesTool 单测（对应 T8 验证）：schema 动态发现语义、单表查询、空库、fail-open。
 */
class ListTablesToolTest {

    private JdbcTemplate demoJdbcTemplate;
    private ToolRegistry toolRegistry;
    private ListTablesTool tool;

    @BeforeEach
    void setUp() {
        demoJdbcTemplate = mock(JdbcTemplate.class);
        toolRegistry = mock(ToolRegistry.class);
        tool = new ListTablesTool(demoJdbcTemplate, toolRegistry);
    }

    private Map<String, Object> tableRow(String name, String comment) {
        Map<String, Object> row = new HashMap<>();
        row.put("table_name", name);
        row.put("table_comment", comment);
        return row;
    }

    private Map<String, Object> columnRow(String name, String type, String comment) {
        Map<String, Object> row = new HashMap<>();
        row.put("column_name", name);
        row.put("data_type", type);
        row.put("column_comment", comment);
        return row;
    }

    @Test
    void registersItself() {
        tool.register();
        verify(toolRegistry).register(tool);
    }

    @Test
    void listsAllTablesWithCommentsAndColumns() {
        when(demoJdbcTemplate.queryForList(anyString())).thenReturn(List.of(
                tableRow("orders", "订单表"),
                tableRow("users", null)
        ));
        when(demoJdbcTemplate.queryForList(anyString(), eq("orders"))).thenReturn(List.of(
                columnRow("id", "bigint", "主键"),
                columnRow("payment_method", "varchar", "支付方式")
        ));
        when(demoJdbcTemplate.queryForList(anyString(), eq("users"))).thenReturn(List.of(
                columnRow("id", "bigint", null)
        ));

        String result = tool.execute(Map.of());

        assertTrue(result.contains("数据库共有 2 张表"));
        assertTrue(result.contains("## orders（订单表）"));
        assertTrue(result.contains("- id (bigint)：主键"));
        assertTrue(result.contains("- payment_method (varchar)：支付方式"));
        // 无表注释时不渲染括号
        assertTrue(result.contains("## users\n"));
        assertFalse(result.contains("## users（"));
        // 无字段注释的 users.id：整行以换行直接结尾，不渲染冒号说明
        assertTrue(result.contains("## users\n  - id (bigint)\n"));
    }

    @Test
    void rendersSingleTableWhenTableArgumentProvided() {
        when(demoJdbcTemplate.queryForList(anyString(), eq("orders"))).thenReturn(List.of(
                columnRow("id", "bigint", "主键")
        ));

        String result = tool.execute(Map.of("table", "orders"));

        assertTrue(result.contains("表 orders 的字段："));
        assertTrue(result.contains("- id (bigint)：主键"));
        // 指定单表时不再查全库表清单
        verify(demoJdbcTemplate, never()).queryForList(anyString());
    }

    @Test
    void reportsEmptyDatabase() {
        when(demoJdbcTemplate.queryForList(anyString())).thenReturn(List.of());

        String result = tool.execute(Map.of());

        assertTrue(result.contains("当前数据库中没有表"));
    }

    @Test
    void reportsUnknownTableWithoutColumns() {
        when(demoJdbcTemplate.queryForList(anyString(), eq("nope"))).thenReturn(List.of());

        String result = tool.execute(Map.of("table", "nope"));

        assertTrue(result.contains("表 nope 的字段："));
        assertTrue(result.contains("（未找到该表或无字段）"));
    }

    @Test
    void failsOpenOnQueryException() {
        when(demoJdbcTemplate.queryForList(anyString()))
                .thenThrow(new RuntimeException("db down"));

        String result = tool.execute(Map.of());

        assertTrue(result.contains("查询表结构失败"));
        // 脱敏：不向 LLM 暴露底层异常细节
        assertFalse(result.contains("db down"));
    }
}

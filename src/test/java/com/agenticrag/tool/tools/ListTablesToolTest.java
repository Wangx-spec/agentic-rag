package com.agenticrag.tool.tools;

import com.agenticrag.dataanalysis.DemoSchemaService;
import com.agenticrag.tool.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ListTablesTool 单测（对应 T8 验证）：schema 动态发现语义、单表查询、空库、fail-open。
 */
class ListTablesToolTest {

    private DemoSchemaService demoSchemaService;
    private ToolRegistry toolRegistry;
    private ListTablesTool tool;

    @BeforeEach
    void setUp() {
        demoSchemaService = mock(DemoSchemaService.class);
        toolRegistry = mock(ToolRegistry.class);
        tool = new ListTablesTool(demoSchemaService, toolRegistry);
    }

    @Test
    void registersItself() {
        tool.register();
        verify(toolRegistry).register(tool);
    }

    @Test
    void listsAllTablesWithCommentsAndColumns() {
        when(demoSchemaService.describeAll()).thenReturn("""
                数据库共有 2 张表：

                ## orders（订单表）
                  列：id(bigint):主键, payment_method(varchar):支付方式
                  样本：[{id=1, payment_method=支付宝}]

                ## users
                  列：id(bigint)
                  样本：[{id=7}]
                """);

        String result = tool.execute(Map.of());

        assertTrue(result.contains("数据库共有 2 张表"));
        assertTrue(result.contains("## orders（订单表）"));
        assertTrue(result.contains("id(bigint):主键"));
        assertTrue(result.contains("payment_method(varchar):支付方式"));
        assertTrue(result.contains("样本"));
        assertTrue(result.contains("支付宝"));
        // 无表注释时不渲染括号
        assertTrue(result.contains("## users\n"));
        assertFalse(result.contains("## users（"));
    }

    @Test
    void rendersSingleTableWhenTableArgumentProvided() {
        when(demoSchemaService.describeTable("orders")).thenReturn("""
                ## orders（订单表）
                  列：id(bigint):主键
                  样本：[{id=1}]
                """);

        String result = tool.execute(Map.of("table", "orders"));

        assertTrue(result.contains("## orders"));
        assertTrue(result.contains("id(bigint):主键"));
        verify(demoSchemaService).describeTable("orders");
    }

    @Test
    void reportsEmptyDatabase() {
        when(demoSchemaService.describeAll()).thenReturn("当前数据库中没有表。");

        String result = tool.execute(Map.of());

        assertTrue(result.contains("当前数据库中没有表"));
    }

    @Test
    void reportsUnknownTableWithoutColumns() {
        when(demoSchemaService.describeTable("nope")).thenReturn("表 nope 的字段：\n  （未找到该表或无字段）\n");

        String result = tool.execute(Map.of("table", "nope"));

        assertTrue(result.contains("表 nope 的字段："));
        assertTrue(result.contains("（未找到该表或无字段）"));
    }

    @Test
    void failsOpenOnQueryException() {
        when(demoSchemaService.describeAll()).thenThrow(new RuntimeException("db down"));

        String result = tool.execute(Map.of());

        assertTrue(result.contains("查询表结构失败"));
        // 脱敏：不向 LLM 暴露底层异常细节
        assertFalse(result.contains("db down"));
    }
}

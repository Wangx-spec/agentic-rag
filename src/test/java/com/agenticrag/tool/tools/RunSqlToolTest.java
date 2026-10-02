package com.agenticrag.tool.tools;

import com.agenticrag.dataanalysis.DataAnalysisProperties;
import com.agenticrag.dataanalysis.SqlSafetyGuard;
import com.agenticrag.dataanalysis.dto.QueryResult;
import com.agenticrag.tool.ToolRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCallback;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * RunSqlTool 单测（对应 T9 验证）：
 * 安全校验前置、超时/行数上限参数、截断标注、脱敏错误提示、table 事件带外通道。
 */
class RunSqlToolTest {

    private JdbcTemplate demoJdbcTemplate;
    private ToolRegistry toolRegistry;
    private DataAnalysisProperties properties;
    private RunSqlTool tool;
    private PreparedStatement ps;

    @BeforeEach
    void setUp() {
        demoJdbcTemplate = mock(JdbcTemplate.class);
        toolRegistry = mock(ToolRegistry.class);
        properties = new DataAnalysisProperties();
        properties.setMaxRows(3);
        properties.setQueryTimeoutSeconds(5);
        tool = new RunSqlTool(demoJdbcTemplate, new SqlSafetyGuard(), properties, toolRegistry);
        RunSqlTool.clearTableEvents();
    }

    @AfterEach
    void tearDown() {
        RunSqlTool.clearTableEvents();
    }

    /**
     * 模拟 demoJdbcTemplate.execute(sql, callback)：把 callback 接到 stub 好的 PreparedStatement 上。
     * 游标用可变数组实现，支持同一 stub 被多次执行（多轮工具调用）。
     */
    private void stubQueryResult(List<String> columns, Object[][] data) throws SQLException {
        ps = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        ResultSetMetaData meta = mock(ResultSetMetaData.class);
        int[] cursor = {0};

        when(ps.executeQuery()).thenAnswer(inv -> {
            cursor[0] = 0; // 每次执行重置游标
            return rs;
        });
        when(rs.getMetaData()).thenReturn(meta);
        when(meta.getColumnCount()).thenReturn(columns.size());
        for (int i = 1; i <= columns.size(); i++) {
            final int idx = i;
            when(meta.getColumnLabel(idx)).thenReturn(columns.get(idx - 1));
        }
        when(rs.next()).thenAnswer(inv -> {
            cursor[0]++;
            return cursor[0] <= data.length;
        });
        for (int col = 1; col <= columns.size(); col++) {
            final int c = col;
            when(rs.getObject(c)).thenAnswer(inv -> data[cursor[0] - 1][c - 1]);
        }

        when(demoJdbcTemplate.<QueryResult>execute(anyString(),
                ArgumentMatchers.<PreparedStatementCallback<QueryResult>>any()))
                .thenAnswer(invocation -> {
                    PreparedStatementCallback<QueryResult> callback = invocation.getArgument(1);
                    try {
                        return callback.doInPreparedStatement(ps);
                    } catch (SQLException e) {
                        throw new RuntimeException(e);
                    }
                });
    }

    @Test
    void registersItself() {
        tool.register();
        verify(toolRegistry).register(tool);
    }

    @Test
    void rejectsMissingSqlArgument() {
        assertTrue(tool.execute(Map.of()).contains("缺少 sql 参数"));
        assertTrue(tool.execute(null).contains("缺少 sql 参数"));
        assertTrue(RunSqlTool.drainTableEvents().isEmpty());
    }

    @Test
    void rejectsUnsafeSqlBeforeTouchingDatabase() {
        String result = tool.execute(Map.of("sql", "DELETE FROM orders"));

        assertTrue(result.contains("查询被拒绝"));
        verifyNoInteractions(demoJdbcTemplate);
        assertTrue(RunSqlTool.drainTableEvents().isEmpty());
    }

    @Test
    void returnsMarkdownAndQueuesTableEvent() throws SQLException {
        stubQueryResult(List.of("platform", "cnt"), new Object[][]{
                {"淘宝", 3L},
                {"天猫", 5L}
        });

        String md = tool.execute(Map.of("sql", "SELECT platform, cnt FROM orders"));

        assertTrue(md.contains("| platform | cnt |"));
        assertTrue(md.contains("| 淘宝 | 3 |"));
        assertFalse(md.contains("已截断"));

        List<Map<String, Object>> events = RunSqlTool.drainTableEvents();
        assertEquals(1, events.size());
        Map<String, Object> payload = events.get(0);
        assertEquals(List.of("platform", "cnt"), payload.get("columns"));
        assertEquals(2, ((List<?>) payload.get("rows")).size());
        assertEquals("SELECT platform, cnt FROM orders", payload.get("sql"));
        assertEquals(Boolean.FALSE, payload.get("truncated"));
        // 事件被消费后再次 drain 为空
        assertTrue(RunSqlTool.drainTableEvents().isEmpty());
    }

    @Test
    void appliesTimeoutAndRowLimitToStatement() throws SQLException {
        stubQueryResult(List.of("platform", "cnt"), new Object[][]{
                {"淘宝", 3L}
        });

        tool.execute(Map.of("sql", "SELECT platform, cnt FROM orders"));

        verify(ps).setQueryTimeout(5);
        // 多取一行用于判断是否截断
        verify(ps).setMaxRows(4);
    }

    @Test
    void truncatesRowsBeyondLimitAndMarksPayload() throws SQLException {
        stubQueryResult(List.of("platform", "cnt"), new Object[][]{
                {"淘宝", 3L},
                {"天猫", 5L},
                {"京东", 7L},
                {"拼多多", 9L},
                {"抖音", 11L}
        });

        String md = tool.execute(Map.of("sql", "SELECT platform, cnt FROM orders"));

        assertTrue(md.contains("已截断"));
        assertTrue(md.contains("共 5 行"));
        assertTrue(md.contains("仅展示前 3 行"));
        assertTrue(md.contains("| 京东 |")); // 第 3 行保留（保留 maxRows 行）
        assertFalse(md.contains("| 抖音 |")); // 第 4 行仅用于探测截断，不进结果

        Map<String, Object> payload = RunSqlTool.drainTableEvents().get(0);
        assertEquals(Boolean.TRUE, payload.get("truncated"));
        assertEquals(3, ((List<?>) payload.get("rows")).size());
    }

    @Test
    void queuesOneTableEventPerExecution() throws SQLException {
        stubQueryResult(List.of("platform", "cnt"), new Object[][]{
                {"淘宝", 3L}
        });

        tool.execute(Map.of("sql", "SELECT platform, cnt FROM orders"));
        tool.execute(Map.of("sql", "SELECT platform, cnt FROM orders"));

        assertEquals(2, RunSqlTool.drainTableEvents().size());
    }

    @Test
    void returnsSanitizedErrorOnDataAccessException() {
        when(demoJdbcTemplate.<QueryResult>execute(anyString(),
                ArgumentMatchers.<PreparedStatementCallback<QueryResult>>any()))
                .thenThrow(new DataAccessResourceFailureException(
                        "Connection refused: jdbc:postgresql://secret-host:5432/demo"));

        String result = tool.execute(Map.of("sql", "SELECT * FROM orders"));

        assertTrue(result.contains("查询执行失败"));
        // 脱敏：不向 LLM 暴露底层连接信息
        assertFalse(result.contains("secret-host"));
        assertTrue(RunSqlTool.drainTableEvents().isEmpty());
    }
}

package com.agenticrag.tool.tools;

import com.agenticrag.dataanalysis.SqlSafetyException;
import com.agenticrag.dataanalysis.SqlSafetyGuard;
import com.agenticrag.dataanalysis.dto.QueryResult;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import com.agenticrag.tool.Tool;
import com.agenticrag.tool.ToolRegistry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 只读 SQL 查询工具（F3/F4）：SqlSafetyGuard 校验 → demo 库执行（超时 + 行数上限）
 * → Markdown 表格返回给 LLM，同时把结构化结果（含 SQL）暂存进 ThreadLocal 供 SSE table 事件带外发送。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "rag.data", name = "enabled", havingValue = "true")
public class RunSqlTool implements Tool {

    private static final String NAME = "run_sql";

    public static final String HARD_FAILURE_PREFIX = "[HARD_FAILURE]";

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "sql": {
                  "type": "string",
                  "description": "只读 SELECT/WITH 查询语句"
                }
              },
              "required": ["sql"]
            }
            """;

    /** 带外通道：工具执行期间暂存表格事件（sql + 结果），由 AgentLoop 在每轮工具调用后 drain 发送 SSE。 */
    private static final ThreadLocal<List<Map<String, Object>>> TABLE_EVENTS = ThreadLocal.withInitial(ArrayList::new);

    @Qualifier("demoJdbcTemplate")
    private final JdbcTemplate demoJdbcTemplate;
    private final SqlSafetyGuard sqlSafetyGuard;
    private final com.agenticrag.dataanalysis.DataAnalysisProperties properties;
    private final ToolRegistry toolRegistry;

    @PostConstruct
    public void register() {
        toolRegistry.register(this);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "对数据库执行只读 SQL 查询（仅 SELECT/WITH），返回 Markdown 表格结果；支持行数上限与超时保护";
    }

    @Override
    public String parametersSchema() {
        return SCHEMA;
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        Object sqlObj = arguments == null ? null : arguments.get("sql");
        if (sqlObj == null || sqlObj.toString().isBlank()) {
            return "错误：缺少 sql 参数";
        }
        String sql = sqlObj.toString();

        try {
            sqlSafetyGuard.validate(sql);
        } catch (SqlSafetyException e) {
            return HARD_FAILURE_PREFIX + "查询被拒绝：" + e.getMessage();
        }

        try {
            QueryResult result = doQuery(sql);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("columns", result.columns());
            payload.put("rows", result.rows());
            payload.put("sql", sql);
            payload.put("truncated", result.truncated());
            TABLE_EVENTS.get().add(payload);
            return result.toMarkdown();
        } catch (DataAccessException e) {
            log.warn("demo 库查询失败: {}", e.getMessage());
            return HARD_FAILURE_PREFIX + "查询执行失败：SQL 有误或数据不可用（请检查语法与表/字段名）。";
        } catch (Exception e) {
            log.warn("demo 库查询出现未预期异常", e);
            return HARD_FAILURE_PREFIX + "查询执行失败，请稍后重试。";
        }
    }

    private QueryResult doQuery(String sql) {
        return demoJdbcTemplate.execute(sql, (org.springframework.jdbc.core.PreparedStatementCallback<QueryResult>) stmt -> {
            stmt.setQueryTimeout(properties.getQueryTimeoutSeconds());
            stmt.setMaxRows(properties.getMaxRows() + 1);
            try (java.sql.ResultSet rs = stmt.executeQuery()) {
                java.sql.ResultSetMetaData meta = rs.getMetaData();
                int colCount = meta.getColumnCount();
                List<String> columns = new ArrayList<>(colCount);
                for (int i = 1; i <= colCount; i++) {
                    String label = meta.getColumnLabel(i);
                    columns.add(label == null || label.isBlank() ? meta.getColumnName(i) : label);
                }
                List<List<Object>> rows = new ArrayList<>();
                int scanned = 0;
                while (rs.next()) {
                    scanned++;
                    if (rows.size() < properties.getMaxRows()) {
                        List<Object> row = new ArrayList<>(colCount);
                        for (int i = 1; i <= colCount; i++) {
                            row.add(rs.getObject(i));
                        }
                        rows.add(row);
                    }
                }
                boolean truncated = scanned > properties.getMaxRows();
                return new QueryResult(columns, rows, truncated, scanned);
            }
        });
    }

    /** 供 AgentLoop 在工具调用后取走本线程暂存的表格事件并清空。 */
    public static List<Map<String, Object>> drainTableEvents() {
        List<Map<String, Object>> events = TABLE_EVENTS.get();
        if (events.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> copy = List.copyOf(events);
        events.clear();
        return copy;
    }

    /** 兜底清理，防止线程复用时残留。 */
    public static void clearTableEvents() {
        TABLE_EVENTS.remove();
    }
}

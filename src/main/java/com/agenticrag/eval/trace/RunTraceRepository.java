package com.agenticrag.eval.trace;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;

@Repository
@Profile("eval")
public class RunTraceRepository {

    private static final String INSERT_SQL = """
            INSERT INTO run_trace (
                run_id, question_id, turn, event_type, tool_name, tool_args_summary,
                result_summary, evidence_ids, evidence_types, routed_intent, critic_verdict, tokens_used
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbcTemplate;

    public RunTraceRepository(@Qualifier("traceJdbcTemplate") JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void batchInsert(List<TraceEvent> events) {
        if (events == null || events.isEmpty()) {
            return;
        }
        jdbcTemplate.batchUpdate(INSERT_SQL, events, events.size(), this::bind);
    }

    public List<TraceEvent> findByRunId(String runId) {
        return jdbcTemplate.query("""
                SELECT run_id, question_id, turn, event_type, tool_name, tool_args_summary,
                       result_summary, evidence_ids, evidence_types, routed_intent, critic_verdict, tokens_used
                FROM run_trace
                WHERE run_id = ?
                ORDER BY question_id, turn, id
                """, mapper(), runId);
    }

    private void bind(PreparedStatement ps, TraceEvent event) throws SQLException {
        ps.setString(1, event.runId());
        ps.setString(2, event.questionId());
        ps.setInt(3, event.turn());
        ps.setString(4, event.eventType());
        ps.setString(5, event.toolName());
        ps.setString(6, event.toolArgsSummary());
        ps.setString(7, event.resultSummary());
        ps.setArray(8, toSqlArray(ps, event.evidenceIds()));
        ps.setArray(9, toSqlArray(ps, event.evidenceTypes()));
        ps.setString(10, event.routedIntent());
        ps.setString(11, event.criticVerdict());
        if (event.tokensUsed() == null) {
            ps.setObject(12, null);
        } else {
            ps.setInt(12, event.tokensUsed());
        }
    }

    private Array toSqlArray(PreparedStatement ps, List<String> values) throws SQLException {
        return ps.getConnection().createArrayOf("text", values == null ? new String[0] : values.toArray(String[]::new));
    }

    private RowMapper<TraceEvent> mapper() {
        return (rs, rowNum) -> new TraceEvent(
                rs.getString("run_id"),
                rs.getString("question_id"),
                rs.getInt("turn"),
                rs.getString("event_type"),
                rs.getString("tool_name"),
                rs.getString("tool_args_summary"),
                rs.getString("result_summary"),
                fromSqlArray(rs.getArray("evidence_ids")),
                fromSqlArray(rs.getArray("evidence_types")),
                rs.getString("routed_intent"),
                rs.getString("critic_verdict"),
                rs.getObject("tokens_used") == null ? null : rs.getInt("tokens_used")
        );
    }

    private List<String> fromSqlArray(Array array) throws SQLException {
        if (array == null) {
            return List.of();
        }
        Object value = array.getArray();
        if (value instanceof String[] strings) {
            return Arrays.asList(strings);
        }
        if (value instanceof Object[] objects) {
            return Arrays.stream(objects).map(String::valueOf).toList();
        }
        return List.of();
    }
}

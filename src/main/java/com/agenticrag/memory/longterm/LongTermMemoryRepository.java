package com.agenticrag.memory.longterm;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.util.List;

/**
 * 长期记忆元数据表读写（M9 F4）：向量存 Qdrant 独立集合（point id = 本表 id），
 * 本表为检索回查与淘汰/清除的事实源。
 */
@Repository
@RequiredArgsConstructor
public class LongTermMemoryRepository {

    private final JdbcTemplate jdbcTemplate;

    public record MemoryEntry(long id, String content, String sourceSessionId, Timestamp createdAt) {
    }

    /** 插入并返回自增 id（作为 Qdrant point id） */
    public long insert(long userId, String content, String sourceSessionId) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO long_term_memories(user_id, content, source_session_id) VALUES (?, ?, ?)",
                    new String[]{"id"});
            ps.setLong(1, userId);
            ps.setString(2, content);
            ps.setString(3, sourceSessionId);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("long_term_memories 插入未返回自增 id");
        }
        return key.longValue();
    }

    /** 按用户取最近 N 条（时间倒序） */
    public List<MemoryEntry> listByUser(long userId, int limit) {
        return jdbcTemplate.query(
                "SELECT id, content, source_session_id, created_at FROM long_term_memories "
                        + "WHERE user_id = ? ORDER BY created_at DESC, id DESC LIMIT ?",
                (rs, rowNum) -> new MemoryEntry(rs.getLong("id"), rs.getString("content"),
                        rs.getString("source_session_id"), rs.getTimestamp("created_at")),
                userId, limit);
    }

    public int countByUser(long userId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM long_term_memories WHERE user_id = ?", Integer.class, userId);
        return count == null ? 0 : count;
    }

    /** 超上限时取最旧的 excess 条 id（淘汰目标） */
    public List<Long> oldestIds(long userId, int excess) {
        return jdbcTemplate.query(
                "SELECT id FROM long_term_memories WHERE user_id = ? ORDER BY created_at ASC, id ASC LIMIT ?",
                (rs, rowNum) -> rs.getLong("id"), userId, excess);
    }

    public void deleteByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        String placeholders = String.join(",", ids.stream().map(id -> "?").toList());
        jdbcTemplate.update("DELETE FROM long_term_memories WHERE id IN (" + placeholders + ")", ids.toArray());
    }

    public List<Long> allIdsByUser(long userId) {
        return jdbcTemplate.query("SELECT id FROM long_term_memories WHERE user_id = ?",
                (rs, rowNum) -> rs.getLong("id"), userId);
    }

    public void deleteByUser(long userId) {
        jdbcTemplate.update("DELETE FROM long_term_memories WHERE user_id = ?", userId);
    }
}

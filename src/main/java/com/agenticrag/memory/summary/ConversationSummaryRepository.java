package com.agenticrag.memory.summary;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 会话摘要表读写：每 (user_id, session_id) 一行滚动更新，
 * last_message_id 记录摘要已覆盖到的消息位置（半窗口重叠判定的判定基准）。
 */
@Repository
@RequiredArgsConstructor
public class ConversationSummaryRepository {
    @Qualifier("jdbcTemplate")
    private final JdbcTemplate jdbcTemplate;

    public record SummaryRow(String content, long lastMessageId) {
    }

    /** 按用户与会话的最小消息 id 区间查询（滑动摘要的取数窗口） */
    public record MessageSlice(long id, String role, String content) {
    }

    public Optional<SummaryRow> find(long userId, String sessionId) {
        List<SummaryRow> rows = jdbcTemplate.query(
                "SELECT content, last_message_id FROM conversation_summaries WHERE user_id = ? AND session_id = ?",
                (rs, rowNum) -> new SummaryRow(rs.getString("content"), rs.getLong("last_message_id")),
                userId, sessionId);
        return rows.stream().findFirst();
    }

    public void save(long userId, String sessionId, String content, long lastMessageId) {
        int updated = jdbcTemplate.update(
                "UPDATE conversation_summaries SET content = ?, last_message_id = ?, updated_at = CURRENT_TIMESTAMP "
                        + "WHERE user_id = ? AND session_id = ?",
                content, lastMessageId, userId, sessionId);
        if (updated > 0) {
            return;
        }
        try {
            jdbcTemplate.update(
                    "INSERT INTO conversation_summaries(user_id, session_id, content, last_message_id) VALUES (?, ?, ?, ?)",
                    userId, sessionId, content, lastMessageId);
        } catch (DuplicateKeyException e) {
            // 并发 insert 竞争时回落为 update，保持 upsert 语义且兼容 H2/PG。
            jdbcTemplate.update(
                    "UPDATE conversation_summaries SET content = ?, last_message_id = ?, updated_at = CURRENT_TIMESTAMP "
                            + "WHERE user_id = ? AND session_id = ?",
                    content, lastMessageId, userId, sessionId);
        }
    }

    /** 会话清除联动：删除该会话的滚动摘要，避免复用 sessionId 时旧摘要污染新会话 */
    public void delete(long userId, String sessionId) {
        jdbcTemplate.update(
                "DELETE FROM conversation_summaries WHERE user_id = ? AND session_id = ?",
                userId, sessionId);
    }

    /** 窗口内最早消息 id（半窗口重叠判定基准：lastCovered 滑出此位置才触发新摘要）；无消息返回 null */
    public Long earliestMessageIdInWindow(long userId, String sessionId, int windowSize) {
        List<Long> ids = jdbcTemplate.query(
                "SELECT id FROM (SELECT id FROM conversation_messages WHERE user_id = ? AND session_id = ? "
                        + "ORDER BY id DESC LIMIT ?) t ORDER BY id ASC LIMIT 1",
                (rs, rowNum) -> rs.getLong("id"), userId, sessionId, windowSize);
        return ids.stream().findFirst().orElse(null);
    }
    
    /** 取 (fromIdExclusive, toIdInclusive] 区间的消息，时间正序（增量摘要的素材） */
    public List<MessageSlice> messagesInRange(long userId, String sessionId, long fromIdExclusive, long toIdInclusive) {
        return jdbcTemplate.query(
                "SELECT id, role, content FROM conversation_messages WHERE user_id = ? AND session_id = ? "
                        + "AND id > ? AND id <= ? ORDER BY id ASC",
                (rs, rowNum) -> new MessageSlice(rs.getLong("id"), rs.getString("role"), rs.getString("content")),
                userId, sessionId, fromIdExclusive, toIdInclusive);
    }

    /** 窗口中间位置的消息 id（本次摘要覆盖到的目标位置）：窗口正序第 floor(size/2) 条 */
    public Long midWindowMessageId(long userId, String sessionId, int windowSize) {
        List<Long> ids = jdbcTemplate.query(
                "SELECT id FROM (SELECT id FROM conversation_messages WHERE user_id = ? AND session_id = ? "
                        + "ORDER BY id DESC LIMIT ?) t ORDER BY id ASC",
                (rs, rowNum) -> rs.getLong("id"), userId, sessionId, windowSize);
        if (ids.isEmpty()) {
            return null;
        }
        return ids.get(ids.size() / 2);
    }
}

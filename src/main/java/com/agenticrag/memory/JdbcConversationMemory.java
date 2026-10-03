package com.agenticrag.memory;

import com.agenticrag.llm.dto.ChatMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 会话记忆（JDBC 持久化实现，M9 F1）：消息落 PG conversation_messages 表，
 * 服务重启 / Redis 故障均不丢会话历史，替代 Redis 的「半持久缓存」定位。
 * <p>
 * 设计要点：
 * - load 按 (user_id, session_id) 以 id 倒序取最近 maxMessages 条，再反转为时间正序；
 * - 不做读缓存（spec 决策：PG 直读，消息量级小，性能优化留后续）；
 * - 仅持久化 role/content——Agent 工具调用中间消息不入记忆，最终回答以普通 assistant 落库；
 * - N1 降级：读写异常仅记日志（读返回空历史），不阻断主问答链路（与 Redis 实现同语义）。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "rag.memory", name = "type", havingValue = "jdbc")
@RequiredArgsConstructor
public class JdbcConversationMemory implements ConversationMemory {

    private static final String LOAD_SQL =
            "SELECT role, content FROM conversation_messages "
                    + "WHERE user_id = ? AND session_id = ? ORDER BY id DESC LIMIT ?";
    private static final String APPEND_SQL =
            "INSERT INTO conversation_messages(user_id, session_id, role, content) VALUES (?, ?, ?, ?)";
    private static final String CLEAR_SQL =
            "DELETE FROM conversation_messages WHERE user_id = ? AND session_id = ?";

    @Qualifier("jdbcTemplate")
    private final JdbcTemplate jdbcTemplate;

    @Override
    public List<ChatMessage> load(long userId, String sessionId, int maxMessages) {
        if (maxMessages <= 0) {
            return List.of();
        }
        try {
            List<ChatMessage> desc = jdbcTemplate.query(LOAD_SQL,
                    (rs, rowNum) -> new ChatMessage(rs.getString("role"), rs.getString("content")),
                    userId, sessionId, maxMessages);
            List<ChatMessage> asc = new ArrayList<>(desc);
            Collections.reverse(asc);
            return asc;
        } catch (Exception e) {
            log.warn("JDBC 会话记忆读取失败，按空历史降级: userId={}, sessionId={}", userId, sessionId, e);
            return List.of();
        }
    }

    @Override
    public void append(long userId, String sessionId, ChatMessage message) {
        try {
            jdbcTemplate.update(APPEND_SQL, userId, sessionId, message.role(), message.content());
        } catch (Exception e) {
            log.warn("JDBC 会话记忆写入失败，仅记日志不影响主链路: userId={}, sessionId={}, role={}",
                    userId, sessionId, message.role(), e);
        }
    }

    @Override
    public void clear(long userId, String sessionId) {
        try {
            jdbcTemplate.update(CLEAR_SQL, userId, sessionId);
        } catch (Exception e) {
            log.warn("JDBC 会话记忆清除失败: userId={}, sessionId={}", userId, sessionId, e);
        }
    }
}
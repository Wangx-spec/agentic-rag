package com.agenticrag.memory.entity;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 用户画像表读写
 */
@Repository
@RequiredArgsConstructor
public class UserProfileRepository {

    private final JdbcTemplate jdbcTemplate;

    public Optional<String> load(long userId) {
        List<String> rows = jdbcTemplate.query(
                "SELECT profile FROM user_profiles WHERE user_id = ?",
                (rs, rowNum) -> rs.getString("profile"), userId);
        return rows.stream().findFirst();
    }

    public void save(long userId, String profileJson) {
        int updated = jdbcTemplate.update(
                "UPDATE user_profiles SET profile = ?, updated_at = CURRENT_TIMESTAMP WHERE user_id = ?",
                profileJson, userId);
        if (updated > 0) {
            return;
        }
        try {
            jdbcTemplate.update(
                    "INSERT INTO user_profiles(user_id, profile) VALUES (?, ?)",
                    userId, profileJson);
        } catch (DuplicateKeyException e) {
            // 并发 insert 竞争时回落为 update，保持 upsert 语义且兼容 H2/PG。
            jdbcTemplate.update(
                    "UPDATE user_profiles SET profile = ?, updated_at = CURRENT_TIMESTAMP WHERE user_id = ?",
                    profileJson, userId);
        }
    }

    public void delete(long userId) {
        jdbcTemplate.update("DELETE FROM user_profiles WHERE user_id = ?", userId);
    }
}

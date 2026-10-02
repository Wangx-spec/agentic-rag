package com.agenticrag.memory.entity;

import com.agenticrag.memory.MemoryProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 实体记忆：按 userId 存储结构化偏好画像，任务开始时渲染为 prompt 片段。
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "rag.memory", name = "type", havingValue = "jdbc")
public class EntityMemoryService {

    private static final TypeReference<Map<String, String>> PROFILE_TYPE = new TypeReference<>() {
    };

    private final UserProfileRepository repository;
    private final MemoryProperties properties;
    private final ObjectMapper objectMapper;

    public EntityMemoryService(UserProfileRepository repository, MemoryProperties properties, ObjectMapper objectMapper) {
        this.repository = repository;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /** 管理接口用：原始结构化条目（保持热度顺序）；异常返回空 Map（N1）。 */
    public Map<String, String> loadEntries(long userId) {
        try {
            return loadProfile(userId);
        } catch (Exception e) {
            log.warn("实体记忆条目读取失败，按空画像降级: userId={}", userId, e);
            return Map.of();
        }
    }

    /**
     * 加载并渲染 prompt 片段；无画像或异常返回空串（拼装侧空串则不注入）。
     */
    public String loadPromptSection(long userId) {
        try {
            Map<String, String> profile = loadProfile(userId);
            if (profile.isEmpty()) {
                return "";
            }
            StringBuilder section = new StringBuilder("## 用户偏好\n");
            profile.forEach((k, v) -> section.append("- ").append(k).append("：").append(v).append("\n"));
            return section.toString();
        } catch (Exception e) {
            log.warn("实体记忆加载失败，按空画像降级: userId={}", userId, e);
            return "";
        }
    }

    /**
     * 字段级合并：新值覆盖同 key 并刷新热度；超上限淘汰最旧。解析失败整体跳过（N1）。
     */
    public void applyUpdates(long userId, List<Map<String, String>> updates) {
        if (updates == null || updates.isEmpty()) {
            return;
        }
        try {
            Map<String, String> profile = new LinkedHashMap<>(loadProfile(userId));
            for (Map<String, String> update : updates) {
                String key = update.get("key");
                String value = update.get("value");
                if (key == null || key.isBlank() || value == null || value.isBlank()) {
                    continue;
                }
                profile.remove(key);
                profile.put(key, value);
            }
            while (profile.size() > properties.getProfileMaxEntries()) {
                String oldest = profile.keySet().iterator().next();
                profile.remove(oldest);
                log.info("实体记忆超上限淘汰最旧条目: userId={}, key={}", userId, oldest);
            }
            repository.save(userId, objectMapper.writeValueAsString(profile));
        } catch (Exception e) {
            log.warn("实体记忆合并失败，跳过本轮写回: userId={}", userId, e);
        }
    }

    public void clear(long userId) {
        try {
            repository.delete(userId);
        } catch (Exception e) {
            log.warn("实体记忆清除失败: userId={}", userId, e);
        }
    }

    private Map<String, String> loadProfile(long userId) throws Exception {
        String json = repository.load(userId).orElse("");
        if (json.isBlank()) {
            return Map.of();
        }
        Map<String, String> parsed = objectMapper.readValue(json, PROFILE_TYPE);
        return parsed == null ? Map.of() : parsed;
    }
}
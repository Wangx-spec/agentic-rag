package com.agenticrag.memory.longterm;

import com.agenticrag.memory.MemoryProperties;
import com.agenticrag.rag.index.EmbeddingClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 长期记忆：有价值结论的摘要条目，跨会话语义检索复用。
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "rag.memory", name = "type", havingValue = "jdbc")
public class LongTermMemoryService {

    private final LongTermMemoryRepository repository;
    private final EmbeddingClient embeddingClient;
    private final MemoryQdrantStore store;
    private final MemoryProperties properties;

    public LongTermMemoryService(LongTermMemoryRepository repository,
                                 EmbeddingClient embeddingClient,
                                 @Autowired(required = false) MemoryQdrantStore store,
                                 MemoryProperties properties) {
        this.repository = repository;
        this.embeddingClient = embeddingClient;
        this.store = store;
        this.properties = properties;
    }

    /**
     * 保存一条长期记忆：embedding → PG insert + Qdrant upsert → 超限淘汰最旧。
     */
    public void save(long userId, String content, String sourceSessionId) {
        if (content == null || content.isBlank()) {
            return;
        }
        try {
            long id = repository.insert(userId, content, sourceSessionId);
            if (store != null) {
                float[] vector = embeddingClient.embed(content);
                store.upsert(id, vector, userId, content, System.currentTimeMillis());
            }
            evictIfNeeded(userId);
        } catch (Exception e) {
            log.warn("长期记忆写入失败，跳过本条: userId={}", userId, e);
        }
    }

    /**
     * 语义检索当前用户的长期记忆；Qdrant 不可用或异常返回空列表（N1）。
     */
    public List<String> search(long userId, String query, int topK) {
        if (store == null || query == null || query.isBlank()) {
            return List.of();
        }
        try {
            float[] vector = embeddingClient.embed(query);
            return store.search(vector, userId, topK).stream()
                    .map(MemoryQdrantStore.MemoryHit::content)
                    .filter(c -> c != null && !c.isBlank())
                    .toList();
        } catch (Exception e) {
            log.warn("长期记忆检索失败，按空结果降级: userId={}", userId, e);
            return List.of();
        }
    }

    /** F8 管理接口用：列出用户全部长期记忆（最近优先，截断 500 条防大结果集） */
    public List<LongTermMemoryRepository.MemoryEntry> listAll(long userId) {
        try {
            return repository.listByUser(userId, 500);
        } catch (Exception e) {
            log.warn("长期记忆列表读取失败: userId={}", userId, e);
            return List.of();
        }
    }

    /** F8 管理接口用：清空用户长期记忆（PG 与 Qdrant 同步清） */
    public void clearAll(long userId) {
        try {
            List<Long> ids = repository.allIdsByUser(userId);
            repository.deleteByUser(userId);
            if (store != null && !ids.isEmpty()) {
                store.delete(ids);
            }
        } catch (Exception e) {
            log.warn("长期记忆清除失败: userId={}", userId, e);
        }
    }

    private void evictIfNeeded(long userId) {
        int count = repository.countByUser(userId);
        int excess = count - properties.getLtmMaxPerUser();
        if (excess <= 0) {
            return;
        }
        List<Long> oldest = repository.oldestIds(userId, excess);
        repository.deleteByIds(oldest);
        if (store != null) {
            store.delete(oldest);
        }
        log.info("长期记忆超上限淘汰最旧 {} 条: userId={}", oldest.size(), userId);
    }
}
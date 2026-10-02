package com.agenticrag.memory.longterm;

import com.agenticrag.memory.MemoryProperties;
import com.agenticrag.rag.index.EmbeddingClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LongTermMemoryService 单测（T7 验证条款）：
 * save 双写（PG 先插拿 id → Qdrant upsert）/ 超限淘汰双侧同步 / search 带 ownerId /
 * clearAll 双清 / store 缺失与异常时降级。
 */
class LongTermMemoryServiceTest {

    private LongTermMemoryRepository repository;
    private EmbeddingClient embeddingClient;
    private MemoryQdrantStore store;
    private LongTermMemoryService service;

    @BeforeEach
    void setUp() {
        repository = mock(LongTermMemoryRepository.class);
        embeddingClient = mock(EmbeddingClient.class);
        store = mock(MemoryQdrantStore.class);
        service = new LongTermMemoryService(repository, embeddingClient, store, new MemoryProperties());
        when(embeddingClient.embed(anyString())).thenReturn(new float[]{0.1f, 0.2f});
    }

    @Test
    void save_先插PG拿到id再写Qdrant() {
        when(repository.insert(7L, "截止日期 3 月 15 日", "s1")).thenReturn(42L);
        when(repository.countByUser(7L)).thenReturn(1);

        service.save(7L, "截止日期 3 月 15 日", "s1");

        verify(store).upsert(eq(42L), any(float[].class), eq(7L), eq("截止日期 3 月 15 日"), anyLong());
    }

    @Test
    void save_超限_淘汰最旧双侧同步删() {
        when(repository.insert(anyLong(), anyString(), anyString())).thenReturn(300L);
        when(repository.countByUser(0L)).thenReturn(201);
        when(repository.oldestIds(0L, 1)).thenReturn(List.of(5L));

        service.save(0L, "新记忆", "s1");

        verify(repository).deleteByIds(List.of(5L));
        verify(store).delete(List.of(5L));
    }

    @Test
    void save_未超限_不淘汰() {
        when(repository.insert(anyLong(), anyString(), anyString())).thenReturn(1L);
        when(repository.countByUser(0L)).thenReturn(50);

        service.save(0L, "x", "s1");

        verify(repository, never()).deleteByIds(anyList());
        verify(store, never()).delete(anyList());
    }

    @Test
    void save_embedding失败_静默跳过不抛错() {
        when(repository.insert(anyLong(), anyString(), anyString())).thenReturn(1L);
        when(embeddingClient.embed(anyString())).thenThrow(new RuntimeException("embed down"));

        service.save(0L, "x", "s1");

        verify(store, never()).upsert(anyLong(), any(float[].class), anyLong(), anyString(), anyLong());
    }

    @Test
    void search_带ownerId过滤返回内容列表() {
        when(store.search(any(float[].class), eq(7L), eq(3))).thenReturn(List.of(
                new MemoryQdrantStore.MemoryHit(1L, "截止日期 3 月 15 日", 0.9f),
                new MemoryQdrantStore.MemoryHit(2L, "偏好英文", 0.8f)));

        List<String> results = service.search(7L, "截止日期", 3);

        assertEquals(2, results.size());
        assertEquals("截止日期 3 月 15 日", results.get(0));
        verify(store).search(any(float[].class), eq(7L), eq(3));
    }

    @Test
    void search_store为null_返回空列表() {
        LongTermMemoryService noStore =
                new LongTermMemoryService(repository, embeddingClient, null, new MemoryProperties());

        assertTrue(noStore.search(0L, "query", 3).isEmpty());
    }

    @Test
    void search_异常_按空结果降级() {
        when(store.search(any(float[].class), anyLong(), anyInt()))
                .thenThrow(new RuntimeException("qdrant down"));

        assertTrue(service.search(0L, "query", 3).isEmpty());
    }

    @Test
    void clearAll_PG与Qdrant同步清() {
        when(repository.allIdsByUser(9L)).thenReturn(List.of(1L, 2L));

        service.clearAll(9L);

        verify(repository).deleteByUser(9L);
        verify(store).delete(List.of(1L, 2L));
    }

    @Test
    void 空content_不写库() {
        service.save(0L, "  ", "s1");

        verify(repository, never()).insert(anyLong(), anyString(), anyString());
    }
}

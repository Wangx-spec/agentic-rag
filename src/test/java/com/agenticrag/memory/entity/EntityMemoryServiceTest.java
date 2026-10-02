package com.agenticrag.memory.entity;

import com.agenticrag.memory.MemoryProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * EntityMemoryService 单测（T6 验证条款）：
 * 画像渲染 / 字段级合并（新增/覆盖）/ 超上限 LRU 淘汰 / 热度刷新 / 空值跳过 / 异常降级。
 */
class EntityMemoryServiceTest {

    private UserProfileRepository repository;
    private EntityMemoryService service;

    @BeforeEach
    void setUp() {
        repository = mock(UserProfileRepository.class);
        service = new EntityMemoryService(repository, new MemoryProperties(), new ObjectMapper());
    }

    @Test
    void 空profile_渲染空串() {
        when(repository.load(0L)).thenReturn(Optional.empty());
        assertEquals("", service.loadPromptSection(0L));
    }

    @Test
    void 有profile_渲染为偏好片段() {
        when(repository.load(0L)).thenReturn(Optional.of("{\"语言偏好\":\"英文\",\"风格\":\"简洁\"}"));

        String section = service.loadPromptSection(0L);

        assertTrue(section.contains("## 用户偏好"));
        assertTrue(section.contains("- 语言偏好：英文"));
        assertTrue(section.contains("- 风格：简洁"));
    }

    @Test
    void 非法JSON_降级为空串且不抛错() {
        when(repository.load(0L)).thenReturn(Optional.of("{bad json"));
        assertEquals("", service.loadPromptSection(0L));
    }

    @Test
    void applyUpdates_新增字段() {
        when(repository.load(0L)).thenReturn(Optional.empty());

        service.applyUpdates(0L, List.of(Map.of("key", "语言偏好", "value", "英文")));

        verify(repository).save(eq(0L), eq("{\"语言偏好\":\"英文\"}"));
    }

    @Test
    void applyUpdates_同key覆盖以最新为准() {
        when(repository.load(0L)).thenReturn(Optional.of("{\"语言偏好\":\"中文\"}"));

        service.applyUpdates(0L, List.of(Map.of("key", "语言偏好", "value", "英文")));

        verify(repository).save(eq(0L), eq("{\"语言偏好\":\"英文\"}"));
    }

    @Test
    void applyUpdates_超上限淘汰最旧key() {
        MemoryProperties props = new MemoryProperties();
        props.setProfileMaxEntries(2);
        service = new EntityMemoryService(repository, props, new ObjectMapper());
        when(repository.load(0L)).thenReturn(Optional.of("{\"a\":\"1\",\"b\":\"2\"}"));

        service.applyUpdates(0L, List.of(Map.of("key", "c", "value", "3")));

        verify(repository).save(eq(0L), argThat(json ->
                json.contains("\"b\":\"2\"") && json.contains("\"c\":\"3\"") && !json.contains("\"a\"")));
    }

    @Test
    void applyUpdates_覆盖刷新热度_被淘汰的是未刷新的key() {
        MemoryProperties props = new MemoryProperties();
        props.setProfileMaxEntries(2);
        service = new EntityMemoryService(repository, props, new ObjectMapper());
        when(repository.load(0L)).thenReturn(Optional.of("{\"a\":\"1\",\"b\":\"2\"}"));

        // 更新 a（刷新其热度到末尾），再插入 c → 淘汰最久未动的 b
        service.applyUpdates(0L, List.of(
                Map.of("key", "a", "value", "1新"),
                Map.of("key", "c", "value", "3")));

        verify(repository).save(eq(0L), argThat(json ->
                json.contains("\"a\":\"1新\"") && json.contains("\"c\":\"3\"") && !json.contains("\"b\"")));
    }

    @Test
    void applyUpdates_空key或空value_跳过该条() {
        when(repository.load(0L)).thenReturn(Optional.empty());

        service.applyUpdates(0L, List.of(
                Map.of("key", "", "value", "x"),
                Map.of("key", "k", "value", ""),
                Map.of("key", "k2", "value", "v2")));

        verify(repository).save(eq(0L), eq("{\"k2\":\"v2\"}"));
    }

    @Test
    void applyUpdates_空更新列表_不写库() {
        service.applyUpdates(0L, List.of());

        verify(repository, org.mockito.Mockito.never()).save(anyLong(), anyString());
    }

    @Test
    void applyUpdates_存储异常_静默跳过不抛错() {
        when(repository.load(0L)).thenReturn(Optional.empty());
        doThrow(new RuntimeException("db down")).when(repository).save(anyLong(), anyString());

        service.applyUpdates(0L, List.of(Map.of("key", "k", "value", "v")));
        // 不抛异常即通过（N1）
    }

    @Test
    void clear_委托删除() {
        service.clear(5L);
        verify(repository).delete(5L);
    }
}

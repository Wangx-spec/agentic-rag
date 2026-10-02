package com.agenticrag.memory;

import com.agenticrag.llm.dto.ChatMessage;
import com.agenticrag.memory.entity.EntityMemoryService;
import com.agenticrag.memory.extract.MemoryExtractionService;
import com.agenticrag.memory.longterm.LongTermMemoryService;
import com.agenticrag.memory.summary.SummaryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MemoryContextAssembler 单测（M9/T8 验证条款）：
 * ①loadHistory 优先摘要编排，摘要异常回退纯窗口，全异常返回空（N1 双重降级）；
 * ②buildMemorySection 实体画像 + 长期记忆拼接，空/异常返回空串；
 * ③onTurnCompleted 摘要走异步执行器 + 提取计数记账；
 * ④clearSessionMemory 联动清摘要与提取计数。
 * 使用同步 Executor（Runnable::run）以便确定性断言异步语义。
 */
class MemoryContextAssemblerTest {

    private ConversationMemory conversationMemory;
    private SummaryService summaryService;
    private EntityMemoryService entityMemoryService;
    private LongTermMemoryService longTermMemoryService;
    private MemoryExtractionService memoryExtractionService;
    private MemoryProperties properties;
    private MemoryContextAssembler assembler;

    @BeforeEach
    void setUp() {
        conversationMemory = mock(ConversationMemory.class);
        summaryService = mock(SummaryService.class);
        entityMemoryService = mock(EntityMemoryService.class);
        longTermMemoryService = mock(LongTermMemoryService.class);
        memoryExtractionService = mock(MemoryExtractionService.class);
        properties = new MemoryProperties();
        properties.setLtmSearchTopk(3);
        assembler = new MemoryContextAssembler(conversationMemory, summaryService,
                entityMemoryService, longTermMemoryService, memoryExtractionService,
                properties, Runnable::run);
    }

    @Test
    void loadHistory_摘要服务可用_优先摘要编排() {
        List<ChatMessage> expected = List.of(ChatMessage.system("滚动摘要"), ChatMessage.user("继续"));
        when(summaryService.loadWithHistory(0L, "s1", 5)).thenReturn(expected);

        List<ChatMessage> actual = assembler.loadHistory(0L, "s1", 5);

        assertEquals(expected, actual);
        verify(conversationMemory, org.mockito.Mockito.never()).load(anyLong(), anyString(), anyInt());
    }

    @Test
    void loadHistory_摘要异常_回退纯窗口_轮数折算翻倍() {
        when(summaryService.loadWithHistory(0L, "s1", 5)).thenThrow(new RuntimeException("db down"));
        List<ChatMessage> window = List.of(ChatMessage.user("在吗"));
        when(conversationMemory.load(0L, "s1", 10)).thenReturn(window);

        List<ChatMessage> actual = assembler.loadHistory(0L, "s1", 5);

        assertEquals(window, actual);
    }

    @Test
    void loadHistory_无摘要服务_直接走会话记忆() {
        MemoryContextAssembler plain = new MemoryContextAssembler(conversationMemory, null,
                entityMemoryService, longTermMemoryService, memoryExtractionService,
                properties, Runnable::run);
        List<ChatMessage> window = List.of(ChatMessage.user("在吗"), ChatMessage.assistant("在的"));
        when(conversationMemory.load(0L, "s1", 10)).thenReturn(window);

        List<ChatMessage> actual = plain.loadHistory(0L, "s1", 5);

        assertEquals(window, actual);
    }

    @Test
    void loadHistory_双重异常_返回空列表不抛出() {
        when(summaryService.loadWithHistory(0L, "s1", 5)).thenThrow(new RuntimeException("db down"));
        when(conversationMemory.load(anyLong(), anyString(), anyInt()))
                .thenThrow(new RuntimeException("window down"));

        List<ChatMessage> actual = assembler.loadHistory(0L, "s1", 5);

        assertTrue(actual.isEmpty());
    }

    @Test
    void buildMemorySection_画像与长期记忆拼接() {
        when(entityMemoryService.loadPromptSection(7L)).thenReturn("## 用户画像\n- 所在地：上海");
        when(longTermMemoryService.search(7L, "用户在哪座城市", 3))
                .thenReturn(List.of("用户定居上海", "用户团队已切换 Java 17"));

        String section = assembler.buildMemorySection(7L, "用户在哪座城市");

        assertTrue(section.contains("## 用户画像"));
        assertTrue(section.contains("## 相关长期记忆"));
        assertTrue(section.contains("[1] 用户定居上海"));
        assertTrue(section.contains("[2] 用户团队已切换 Java 17"));
    }

    @Test
    void buildMemorySection_只有画像_不长期检索() {
        when(entityMemoryService.loadPromptSection(7L)).thenReturn("## 用户画像\n- 语言：中文");
        when(longTermMemoryService.search(anyLong(), anyString(), anyInt())).thenReturn(List.of());

        String section = assembler.buildMemorySection(7L, "你好");

        assertTrue(section.contains("## 用户画像"));
        assertTrue(!section.contains("## 相关长期记忆"));
    }

    @Test
    void buildMemorySection_内容全空_返回空串() {
        when(entityMemoryService.loadPromptSection(7L)).thenReturn(" ");
        when(longTermMemoryService.search(anyLong(), anyString(), anyInt())).thenReturn(List.of());

        String section = assembler.buildMemorySection(7L, "你好");

        assertEquals("", section);
    }

    @Test
    void buildMemorySection_服务异常_返回空串不抛出() {
        when(entityMemoryService.loadPromptSection(7L)).thenThrow(new RuntimeException("profile query fails"));
        when(longTermMemoryService.search(anyLong(), anyString(), anyInt()))
                .thenThrow(new RuntimeException("qdrant down"));

        String section = assembler.buildMemorySection(7L, "用户在哪座城市");

        assertEquals("", section);
    }

    @Test
    void onTurnCompleted_摘要压缩入执行器_提取计数记账() {
        assembler.onTurnCompleted(0L, "s1");

        // 同步 Executor 下可直接断言：摘要压缩已执行（N5 异步语义由生产 memoryExecutor 承担）
        verify(summaryService).compressIfNeeded(0L, "s1");
        verify(memoryExtractionService).onTurnCompleted(0L, "s1");
    }

    @Test
    void onTurnCompleted_执行器拒绝或服务失败_不抛出() {
        Executor rejecting = task -> {
            throw new RuntimeException("queue full");
        };
        MemoryContextAssembler rejectingAssembler = new MemoryContextAssembler(conversationMemory,
                summaryService, entityMemoryService, longTermMemoryService, memoryExtractionService,
                properties, rejecting);

        rejectingAssembler.onTurnCompleted(0L, "s1");

        // 摘要压缩投递失败则提取记账也不应发生（try 整体包裹）
        verify(memoryExtractionService, org.mockito.Mockito.never()).onTurnCompleted(anyLong(), anyString());
    }

    @Test
    void clearSessionMemory_清摘要与提取计数() {
        assembler.clearSessionMemory(0L, "s1");

        verify(summaryService).clear(0L, "s1");
        verify(memoryExtractionService).reset(0L, "s1");
    }

    @Test
    void clearSessionMemory_联动异常_不抛出() {
        doThrow(new RuntimeException("delete summary fails")).when(summaryService).clear(0L, "s1");

        assembler.clearSessionMemory(0L, "s1");

        verify(entityMemoryService, org.mockito.Mockito.never()).applyUpdates(anyLong(), argThat(u -> true));
    }
}

package com.agenticrag.memory.extract;

import com.agenticrag.llm.dto.ChatMessage;
import com.agenticrag.memory.ConversationMemory;
import com.agenticrag.memory.MemoryLlmClient;
import com.agenticrag.memory.MemoryProperties;
import com.agenticrag.memory.entity.EntityMemoryService;
import com.agenticrag.memory.longterm.LongTermMemoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MemoryExtractionService 单测（M9/T10）：
 * ①未达周期不提取；②到达周期取窗口调小模型并分发画像/长期记忆写回（含 ```json 包裹容错）；
 * ③小模型失败跳过本轮且计数清零后需重新累计；④reset 清残留计数；
 * ⑤超长长期记忆截断到 200 字符；⑥extract 开关关闭不记账。
 */
class MemoryExtractionServiceTest {

    private ConversationMemory conversationMemory;
    private EntityMemoryService entityMemoryService;
    private LongTermMemoryService longTermMemoryService;
    private MemoryLlmClient memoryLlmClient;
    private MemoryProperties properties;
    private MemoryExtractionService service;

    @BeforeEach
    void setUp() {
        conversationMemory = mock(ConversationMemory.class);
        entityMemoryService = mock(EntityMemoryService.class);
        longTermMemoryService = mock(LongTermMemoryService.class);
        memoryLlmClient = mock(MemoryLlmClient.class);
        properties = new MemoryProperties();
        properties.getExtract().setIntervalTurns(2);
        service = new MemoryExtractionService(conversationMemory, entityMemoryService,
                longTermMemoryService, memoryLlmClient, properties);
    }

    @Test
    void 未达周期_不触发小模型() {
        service.onTurnCompleted(0L, "s1"); // interval=2，仅累计 1 轮
        service.extractDueSessions();

        verify(memoryLlmClient, never()).chat(anyList());
        verify(entityMemoryService, never()).applyUpdates(anyLong(), anyList());
        verify(longTermMemoryService, never()).save(anyLong(), anyString(), anyString());
    }

    @Test
    void 到达周期_提取并写回画像与长期记忆() {
        service.onTurnCompleted(0L, "s1");
        service.onTurnCompleted(0L, "s1"); // 达到 interval=2

        // 提取窗口应为 (2+1)*2=6 条消息
        when(conversationMemory.load(eq(0L), eq("s1"), anyInt())).thenReturn(List.of(
                ChatMessage.system("滚动摘要（非提取素材）"),
                ChatMessage.user("我定居上海，团队已切换 Java 17"),
                ChatMessage.assistant("已记录")));
        when(memoryLlmClient.chat(anyList())).thenReturn("""
                ```json
                {"profileUpdates":[{"key":"所在地","value":"上海"}],"longTermMemories":["团队已切换 Java 17"]}
                ```
                """);

        service.extractDueSessions();

        // prompt 素材跳过 system 消息：验证喂给小模型的对话含窗口原文内容
        verify(memoryLlmClient).chat(argThat(messages ->
                messages.size() == 2
                        && messages.get(1).content().contains("我定居上海，团队已切换 Java 17")));
        verify(entityMemoryService).applyUpdates(eq(0L), eq(List.of(Map.of("key", "所在地", "value", "上海"))));
        verify(longTermMemoryService).save(0L, "团队已切换 Java 17", "s1");
    }

    @Test
    void 小模型失败_跳过本轮且计数清零后须重新累计() {
        service.onTurnCompleted(0L, "s1");
        service.onTurnCompleted(0L, "s1");
        when(conversationMemory.load(eq(0L), eq("s1"), anyInt())).thenReturn(List.of(
                ChatMessage.user("hi"), ChatMessage.assistant("hi")));
        when(memoryLlmClient.chat(anyList())).thenThrow(new RuntimeException("llm down"));

        service.extractDueSessions(); // 第一次：失败，计数被取走清零

        verify(longTermMemoryService, never()).save(anyLong(), anyString(), anyString());

        service.onTurnCompleted(0L, "s1"); // 重新累计 1 轮
        service.extractDueSessions();      // 未再次达到 interval，不应再调小模型

        // 仅第一次失败时调用过 1 次 chat（含抛异常），第二次扫描未产生新调用
        verify(memoryLlmClient, times(1)).chat(anyList());
    }

    @Test
    void reset_清除残留计数避免复用会话时误触发() {
        service.onTurnCompleted(0L, "s1"); // 累计 1 轮
        service.reset(0L, "s1");
        service.onTurnCompleted(0L, "s1"); // 计数从 0 重新累计 → 仍只有 1 轮

        service.extractDueSessions();

        verify(memoryLlmClient, never()).chat(anyList());
    }

    @Test
    void 小模型返回无内容_空数组不写回() {
        service.onTurnCompleted(0L, "s1");
        service.onTurnCompleted(0L, "s1");
        when(conversationMemory.load(eq(0L), eq("s1"), anyInt())).thenReturn(List.of(
                ChatMessage.user("你好"), ChatMessage.assistant("你好")));
        when(memoryLlmClient.chat(anyList())).thenReturn("{\"profileUpdates\":[],\"longTermMemories\":[]}");

        service.extractDueSessions();

        // 空数组仍以空列表分发画像更新（服务侧无实际写入），但长期记忆不写回
        verify(entityMemoryService).applyUpdates(eq(0L), eq(List.of()));
        verify(longTermMemoryService, never()).save(anyLong(), anyString(), anyString());
    }

    @Test
    void 超长长期记忆_截断到200字符() {
        service.onTurnCompleted(0L, "s1");
        service.onTurnCompleted(0L, "s1");
        when(conversationMemory.load(eq(0L), eq("s1"), anyInt())).thenReturn(List.of(
                ChatMessage.user("hi"), ChatMessage.assistant("hi")));
        String longMemory = "超".repeat(500);
        when(memoryLlmClient.chat(anyList())).thenReturn(
                "{\"profileUpdates\":[],\"longTermMemories\":[\"" + longMemory + "\"]}");

        service.extractDueSessions();

        verify(longTermMemoryService).save(eq(0L), argThat(content -> content.length() == 200), eq("s1"));
    }

    @Test
    void extract开关关闭_onTurnCompleted不记账() {
        properties.getExtract().setEnabled(false);
        service.onTurnCompleted(0L, "s1");
        service.onTurnCompleted(0L, "s1");

        service.extractDueSessions();

        verify(memoryLlmClient, never()).chat(anyList());
    }

    @Test
    void 空窗口_不调小模型() {
        service.onTurnCompleted(0L, "s1");
        service.onTurnCompleted(0L, "s1");
        when(conversationMemory.load(eq(0L), eq("s1"), anyInt())).thenReturn(List.of());

        service.extractDueSessions();

        verify(memoryLlmClient, never()).chat(anyList());
    }

    @Test
    void 提取失败_不影响其他会话() {
        service.onTurnCompleted(0L, "bad");  // 该会话小模型调用失败
        service.onTurnCompleted(0L, "bad");
        service.onTurnCompleted(1L, "good"); // 该会话成功
        service.onTurnCompleted(1L, "good");

        when(conversationMemory.load(eq(0L), eq("bad"), anyInt())).thenThrow(new RuntimeException("db down"));
        when(conversationMemory.load(eq(1L), eq("good"), anyInt())).thenReturn(List.of(
                ChatMessage.user("你好"), ChatMessage.assistant("你好")));
        when(memoryLlmClient.chat(anyList())).thenReturn("{\"profileUpdates\":[],\"longTermMemories\":[]}");

        service.extractDueSessions(); // bad 会话 load 抛异常被吞，good 会话继续

        // good 会话会真正调用小模型（空结果不写回但已执行）
        verify(memoryLlmClient).chat(anyList());
        assertTrue(true);
    }
}

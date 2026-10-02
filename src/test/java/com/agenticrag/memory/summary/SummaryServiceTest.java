package com.agenticrag.memory.summary;

import com.agenticrag.llm.dto.ChatMessage;
import com.agenticrag.memory.ConversationMemory;
import com.agenticrag.memory.MemoryLlmClient;
import com.agenticrag.memory.MemoryProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * SummaryService 单测（T5 验证条款）：
 * ①未滑出窗口不触发；②触发时区间与 prompt 含既有摘要；
 * ③inflight 期间二次调用直接返回；④loadWithHistory 摘要在最前且顺序正确；
 * ⑤LLM/读取异常静默降级；⑥超长摘要按 maxChars 截断保留末尾。
 */
class SummaryServiceTest {

    private ConversationMemory conversationMemory;
    private ConversationSummaryRepository repository;
    private MemoryLlmClient llmClient;
    private SummaryService summaryService;

    @BeforeEach
    void setUp() {
        conversationMemory = mock(ConversationMemory.class);
        repository = mock(ConversationSummaryRepository.class);
        llmClient = mock(MemoryLlmClient.class);
        summaryService = new SummaryService(conversationMemory, repository, llmClient, new MemoryProperties());
    }

    @Test
    void 窗口内最早消息仍在摘要覆盖范围内_不触发() {
        when(repository.earliestMessageIdInWindow(eq(0L), eq("s1"), anyInt())).thenReturn(100L);
        when(repository.find(0L, "s1")).thenReturn(Optional.of(
                new ConversationSummaryRepository.SummaryRow("旧摘要", 120L)));

        summaryService.compressIfNeeded(0L, "s1");

        verify(llmClient, never()).chat(anyList());
        verify(repository, never()).save(anyLong(), anyString(), anyString(), anyLong());
    }

    @Test
    void 摘要位置滑出窗口_触发且prompt含既有摘要() {
        when(repository.earliestMessageIdInWindow(eq(0L), eq("s1"), anyInt())).thenReturn(200L);
        when(repository.find(0L, "s1")).thenReturn(Optional.of(
                new ConversationSummaryRepository.SummaryRow("旧摘要：用户偏好英文", 100L)));
        when(repository.midWindowMessageId(0L, "s1", 16)).thenReturn(210L);
        when(repository.messagesInRange(0L, "s1", 100L, 210L)).thenReturn(List.of(
                new ConversationSummaryRepository.MessageSlice(105L, "user", "项目截止 3 月 15 日"),
                new ConversationSummaryRepository.MessageSlice(106L, "assistant", "已记录")));
        when(llmClient.chat(anyList())).thenReturn("合并摘要：偏好英文；截止 3 月 15 日");

        summaryService.compressIfNeeded(0L, "s1");

        verify(llmClient).chat(argThat(messages ->
                messages.get(0).role().equals("system")
                        && messages.get(1).content().contains("旧摘要：用户偏好英文")
                        && messages.get(1).content().contains("项目截止 3 月 15 日")));
        verify(repository).save(0L, "s1", "合并摘要：偏好英文；截止 3 月 15 日", 210L);
    }

    @Test
    void 首次摘要_无既有摘要时lastCovered从零起() {
        when(repository.earliestMessageIdInWindow(eq(0L), eq("s1"), anyInt())).thenReturn(50L);
        when(repository.find(0L, "s1")).thenReturn(Optional.empty());
        when(repository.midWindowMessageId(0L, "s1", 16)).thenReturn(60L);
        // Optional.empty() 走两次：先 lastMessageId 判定（orElse 0L），再既有摘要取 content（orElse ""）
        when(repository.messagesInRange(0L, "s1", 0L, 60L)).thenReturn(List.of(
                new ConversationSummaryRepository.MessageSlice(55L, "user", "你好")));
        when(llmClient.chat(anyList())).thenReturn("首次摘要");

        summaryService.compressIfNeeded(0L, "s1");

        verify(repository).save(0L, "s1", "首次摘要", 60L);
    }

    @Test
    void llm异常_静默降级不写库() {
        when(repository.earliestMessageIdInWindow(eq(0L), eq("s1"), anyInt())).thenReturn(200L);
        when(repository.find(0L, "s1")).thenReturn(Optional.empty());
        when(repository.midWindowMessageId(eq(0L), eq("s1"), anyInt())).thenReturn(210L);
        when(repository.messagesInRange(anyLong(), anyString(), anyLong(), anyLong())).thenReturn(List.of(
                new ConversationSummaryRepository.MessageSlice(205L, "user", "x")));
        when(llmClient.chat(anyList())).thenThrow(new RuntimeException("llm down"));

        summaryService.compressIfNeeded(0L, "s1");

        verify(repository, never()).save(anyLong(), anyString(), anyString(), anyLong());
    }

    @Test
    void inflight期间_二次调用直接返回() {
        when(repository.earliestMessageIdInWindow(eq(0L), eq("s1"), anyInt())).thenReturn(200L);
        when(repository.find(0L, "s1")).thenReturn(Optional.empty());
        when(repository.midWindowMessageId(eq(0L), eq("s1"), anyInt())).thenReturn(210L);
        when(repository.messagesInRange(anyLong(), anyString(), anyLong(), anyLong())).thenReturn(List.of(
                new ConversationSummaryRepository.MessageSlice(205L, "user", "x")));
        when(llmClient.chat(anyList())).thenAnswer(invocation -> {
            // 在 llm 调用阻塞期间重入（inflight 未释放），应直接返回
            summaryService.compressIfNeeded(0L, "s1");
            return "摘要";
        });

        summaryService.compressIfNeeded(0L, "s1");

        verify(llmClient, times(1)).chat(anyList());
    }

    @Test
    void 摘要开关关闭_不触发() {
        MemoryProperties props = new MemoryProperties();
        props.getSummary().setEnabled(false);
        summaryService = new SummaryService(conversationMemory, repository, llmClient, props);

        summaryService.compressIfNeeded(0L, "s1");

        verify(repository, never()).earliestMessageIdInWindow(anyLong(), anyString(), anyInt());
        verify(llmClient, never()).chat(anyList());
    }

    @Test
    void loadWithHistory_摘要作为首条system消息() {
        when(conversationMemory.load(0L, "s1", 16)).thenReturn(List.of(
                ChatMessage.user("最近问题"), ChatMessage.assistant("最近回答")));
        when(repository.find(0L, "s1")).thenReturn(Optional.of(
                new ConversationSummaryRepository.SummaryRow("历史摘要内容", 100L)));

        List<ChatMessage> history = summaryService.loadWithHistory(0L, "s1", 8);

        assertEquals(3, history.size());
        assertEquals("system", history.get(0).role());
        assertTrue(history.get(0).content().startsWith("[历史摘要] "));
        assertEquals("最近问题", history.get(1).content());
        assertEquals("最近回答", history.get(2).content());
    }

    @Test
    void loadWithHistory_摘要读取异常_退化为纯窗口() {
        when(conversationMemory.load(0L, "s1", 16)).thenReturn(List.of(ChatMessage.user("hi")));
        when(repository.find(0L, "s1")).thenThrow(new RuntimeException("db down"));

        List<ChatMessage> history = summaryService.loadWithHistory(0L, "s1", 8);

        assertEquals(1, history.size());
        assertEquals("hi", history.get(0).content());
    }

    @Test
    void 摘要超长_按maxChars截断保留末尾() {
        MemoryProperties props = new MemoryProperties();
        props.getSummary().setMaxChars(10);
        summaryService = new SummaryService(conversationMemory, repository, llmClient, props);

        when(repository.earliestMessageIdInWindow(eq(0L), eq("s1"), anyInt())).thenReturn(200L);
        when(repository.find(0L, "s1")).thenReturn(Optional.empty());
        when(repository.midWindowMessageId(eq(0L), eq("s1"), anyInt())).thenReturn(210L);
        when(repository.messagesInRange(anyLong(), anyString(), anyLong(), anyLong())).thenReturn(List.of(
                new ConversationSummaryRepository.MessageSlice(205L, "user", "x")));
        when(llmClient.chat(anyList())).thenReturn("一二三四五六七八九十超出的字");

        summaryService.compressIfNeeded(0L, "s1");

        // 原文 14 字符，maxChars=10 → 保留末尾 10 字符「五六七八九十超出的字」
        verify(repository).save(eq(0L), eq("s1"), eq("五六七八九十超出的字"), eq(210L));
    }
}

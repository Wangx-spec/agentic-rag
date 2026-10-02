package com.agenticrag.memory;

import com.agenticrag.AgenticRagApplication;
import com.agenticrag.llm.LlmClient;
import com.agenticrag.llm.dto.ChatMessage;
import com.agenticrag.llm.dto.LlmResponse;
import com.agenticrag.llm.dto.ToolCall;
import com.agenticrag.memory.entity.EntityMemoryService;
import com.agenticrag.memory.extract.MemoryExtractionService;
import com.agenticrag.memory.longterm.LongTermMemoryRepository;
import com.agenticrag.memory.longterm.LongTermMemoryService;
import com.agenticrag.memory.longterm.MemoryQdrantStore;
import com.agenticrag.memory.summary.SummaryService;
import com.agenticrag.rag.index.EmbeddingClient;
import com.agenticrag.service.ChatEventSink;
import com.agenticrag.service.ChatMode;
import com.agenticrag.service.ChatResult;
import com.agenticrag.service.ChatService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M9/Wave E 集成验收：H2 + jdbc 记忆模式下覆盖 checklist 的 8 个端到端场景。
 */
@SpringBootTest(classes = AgenticRagApplication.class)
@AutoConfigureMockMvc
@Sql(scripts = "classpath:db/init-h2.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class MemoryIntegrationTest {

    private static final Path DATA_DIR = createTempDir();

    @Autowired
    private ChatService chatService;

    @Autowired
    private SummaryService summaryService;

    @Autowired
    private MemoryExtractionService memoryExtractionService;

    @Autowired
    private EntityMemoryService entityMemoryService;

    @Autowired
    private LongTermMemoryService longTermMemoryService;

    @Autowired
    private MemoryContextAssembler memoryAssembler;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    @Qualifier("memoryExecutor")
    private ThreadPoolTaskExecutor memoryExecutor;

    @MockBean
    private EmbeddingClient embeddingClient;

    @MockBean
    private LlmClient llmClient;

    @MockBean
    private MemoryLlmClient memoryLlmClient;

    @MockBean
    private MemoryQdrantStore memoryQdrantStore;

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:m9_memory_integration;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        registry.add("spring.datasource.username", () -> "sa");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.datasource.driver-class-name", () -> "org.h2.Driver");
        registry.add("rag.vector.type", () -> "memory");
        registry.add("rag.data-dir", () -> DATA_DIR.toString());
        registry.add("llm.api-key", () -> "test-mock-key");
        registry.add("llm.memory-rounds", () -> "8");
    }

    @BeforeEach
    void setUp() {
        reset(embeddingClient, llmClient, memoryLlmClient, memoryQdrantStore);
        when(embeddingClient.embed(anyString())).thenReturn(new float[]{0.1f, 0.2f});
        when(embeddingClient.embedBatch(anyList())).thenAnswer(invocation -> {
            List<?> inputs = invocation.getArgument(0);
            return inputs.stream().map(ignored -> new float[]{0.1f, 0.2f}).toList();
        });
        when(llmClient.chatStream(anyList(), any(LlmClient.StreamListener.class))).thenReturn("集成测试回答");
        when(memoryLlmClient.chat(anyList())).thenAnswer(invocation -> {
            List<ChatMessage> messages = invocation.getArgument(0);
            String system = messages.isEmpty() ? "" : messages.get(0).content();
            if (system.contains("对话记忆提取助手")) {
                return "{\"profileUpdates\":[],\"longTermMemories\":[]}";
            }
            return "滚动摘要";
        });
    }

    @Test
    void 场景1_jdbc持久化同会话上下文可延续() {
        chatService.chat(11L, "ac1", "我的项目代号是 M9", ChatMode.PLAIN, noopSink());
        chatService.chat(11L, "ac1", "刚才的项目代号是什么", ChatMode.PLAIN, noopSink());
        awaitMemoryIdle();

        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM conversation_messages WHERE user_id = ? AND session_id = ?
                """, Integer.class, 11L, "ac1");
        assertEquals(4, count);

        ArgumentCaptor<List<ChatMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(llmClient, atLeastOnce()).chatStream(captor.capture(), any(LlmClient.StreamListener.class));
        List<ChatMessage> secondCall = captor.getAllValues().get(1);
        assertTrue(secondCall.stream().anyMatch(message -> message.content().contains("我的项目代号是 M9")));
    }

    @Test
    void 场景2_偏好提取后跨会话注入用户画像() {
        when(memoryLlmClient.chat(anyList())).thenReturn("""
                {"profileUpdates":[{"key":"语言偏好","value":"English"}],"longTermMemories":[]}
                """);

        for (int i = 0; i < 5; i++) {
            chatService.chat(21L, "ac2-a", "以后回答都用英文 " + i, ChatMode.PLAIN, noopSink());
        }
        awaitMemoryIdle();
        memoryExtractionService.extractDueSessions();

        assertTrue(entityMemoryService.loadPromptSection(21L).contains("English"));

        chatService.chat(21L, "ac2-b", "新会话也记得我的偏好吗", ChatMode.PLAIN, noopSink());
        ArgumentCaptor<List<ChatMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(llmClient, atLeastOnce()).chatStream(captor.capture(), any(LlmClient.StreamListener.class));
        List<ChatMessage> latest = captor.getAllValues().get(captor.getAllValues().size() - 1);
        assertTrue(latest.stream().anyMatch(message -> message.content().contains("## 用户偏好")
                && message.content().contains("English")));
    }

    @Test
    void 场景3_结论长期记忆跨会话检索且Agent可调用searchMemory() {
        when(memoryLlmClient.chat(anyList())).thenReturn("""
                {"profileUpdates":[],"longTermMemories":["项目截止日期是明年 3 月 15 日"]}
                """);

        for (int i = 0; i < 5; i++) {
            chatService.chat(31L, "ac3-a", "记住：项目截止日期是明年 3 月 15 日 " + i, ChatMode.PLAIN, noopSink());
        }
        awaitMemoryIdle();
        memoryExtractionService.extractDueSessions();

        List<LongTermMemoryRepository.MemoryEntry> entries = longTermMemoryService.listAll(31L);
        assertFalse(entries.isEmpty());
        assertTrue(entries.get(0).content().contains("3 月 15 日"));

        when(memoryQdrantStore.search(any(float[].class), eq(31L), anyInt())).thenReturn(List.of(
                new MemoryQdrantStore.MemoryHit(entries.get(0).id(), "项目截止日期是明年 3 月 15 日", 0.9f)));
        assertTrue(memoryAssembler.buildMemorySection(31L, "截止日期").contains("3 月 15 日"));

        when(llmClient.chatWithTools(anyList(), anyList())).thenReturn(
                LlmResponse.withToolCalls("", List.of(new ToolCall("call-1", "search_memory", "{\"query\":\"截止日期\"}"))),
                LlmResponse.withContent("之前确认的截止日期是明年 3 月 15 日。"));

        ChatResult result = chatService.chat(31L, "ac3-b", "回忆一下之前说过的截止日期", ChatMode.AGENT, noopSink());

        assertEquals(ChatMode.AGENT, result.executedMode());
        assertTrue(result.answer().contains("3 月 15 日"));
        assertTrue(result.toolInvocations().stream().anyMatch(invocation -> invocation.toolName().equals("search_memory")));
    }

    @Test
    void 场景4_长会话触发滚动摘要且发送给主模型的窗口受控() {
        when(memoryLlmClient.chat(anyList())).thenReturn("历史摘要：早期 30 轮已压缩");
        insertConversationMessages(41L, "ac4", 40);

        summaryService.compressIfNeeded(41L, "ac4");
        chatService.chat(41L, "ac4", "检查窗口", ChatMode.PLAIN, noopSink());

        ArgumentCaptor<List<ChatMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(llmClient).chatStream(captor.capture(), any(LlmClient.StreamListener.class));
        List<ChatMessage> messages = captor.getValue();
        assertTrue(messages.size() <= 18);
        assertTrue(messages.stream().anyMatch(message -> message.role().equals("system")
                && message.content().startsWith("[历史摘要] ")));
    }

    @Test
    void 场景5_十轮对话提取类小模型调用次数受控() {
        AtomicInteger extractCalls = new AtomicInteger();
        when(memoryLlmClient.chat(anyList())).thenAnswer(invocation -> {
            List<ChatMessage> messages = invocation.getArgument(0);
            String system = messages.isEmpty() ? "" : messages.get(0).content();
            if (system.contains("对话记忆提取助手")) {
                extractCalls.incrementAndGet();
                return "{\"profileUpdates\":[],\"longTermMemories\":[]}";
            }
            return "滚动摘要";
        });

        for (int i = 0; i < 10; i++) {
            chatService.chat(51L, "ac5", "第 " + i + " 轮", ChatMode.PLAIN, noopSink());
        }
        awaitMemoryIdle();
        memoryExtractionService.extractDueSessions();

        assertTrue(extractCalls.get() > 0);
        assertTrue(extractCalls.get() <= 2);
    }

    @Test
    void 场景6_qdrant异常时长期记忆降级但问答正常返回() {
        doThrow(new RuntimeException("qdrant down"))
                .when(memoryQdrantStore).search(any(float[].class), anyLong(), anyInt());

        ChatResult result = assertDoesNotThrow(() ->
                chatService.chat(61L, "ac6", "需要检索长期记忆的问题", ChatMode.PLAIN, noopSink()));

        assertNotNull(result.answer());
        assertFalse(result.answer().isBlank());
        assertFalse(result.degraded());
    }

    @Test
    void 场景7_管理端点查看和清除画像与长期记忆后不再注入() throws Exception {
        entityMemoryService.applyUpdates(71L, List.of(Map.of("key", "所在地", "value", "上海")));
        longTermMemoryService.save(71L, "用户确认项目使用 Java 17", "ac7");

        mockMvc.perform(get("/api/memory/profile/71"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.所在地").value("上海"));
        mockMvc.perform(get("/api/memory/longterm/71"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].content").value("用户确认项目使用 Java 17"));

        mockMvc.perform(delete("/api/memory/profile/71"))
                .andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/memory/longterm/71"))
                .andExpect(status().isNoContent());

        chatService.chat(71L, "ac7-next", "还有我的画像吗", ChatMode.PLAIN, noopSink());
        ArgumentCaptor<List<ChatMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(llmClient).chatStream(captor.capture(), any(LlmClient.StreamListener.class));
        assertTrue(captor.getValue().stream().noneMatch(message -> message.content().contains("## 用户偏好")));
        assertTrue(entityMemoryService.loadEntries(71L).isEmpty());
        assertTrue(longTermMemoryService.listAll(71L).isEmpty());
    }

    @Test
    void 场景8_不同userId同会话互不可见() {
        chatService.chat(81L, "shared", "我偏好英文", ChatMode.PLAIN, noopSink());
        chatService.chat(82L, "shared", "我偏好中文", ChatMode.PLAIN, noopSink());
        entityMemoryService.applyUpdates(81L, List.of(Map.of("key", "语言偏好", "value", "英文")));
        entityMemoryService.applyUpdates(82L, List.of(Map.of("key", "语言偏好", "value", "中文")));
        when(memoryQdrantStore.search(any(float[].class), eq(81L), anyInt())).thenReturn(List.of(
                new MemoryQdrantStore.MemoryHit(1L, "用户 81 的长期记忆", 0.9f)));
        when(memoryQdrantStore.search(any(float[].class), eq(82L), anyInt())).thenReturn(List.of(
                new MemoryQdrantStore.MemoryHit(2L, "用户 82 的长期记忆", 0.9f)));

        String user81Section = memoryAssembler.buildMemorySection(81L, "偏好");
        String user82Section = memoryAssembler.buildMemorySection(82L, "偏好");

        assertTrue(user81Section.contains("英文"));
        assertTrue(user81Section.contains("用户 81 的长期记忆"));
        assertFalse(user81Section.contains("中文"));
        assertFalse(user81Section.contains("用户 82 的长期记忆"));
        assertTrue(user82Section.contains("中文"));
        assertTrue(user82Section.contains("用户 82 的长期记忆"));
        assertFalse(user82Section.contains("英文"));
        assertFalse(user82Section.contains("用户 81 的长期记忆"));
    }

    private void insertConversationMessages(long userId, String sessionId, int count) {
        for (int i = 1; i <= count; i++) {
            String role = i % 2 == 1 ? "user" : "assistant";
            jdbcTemplate.update("""
                    INSERT INTO conversation_messages(user_id, session_id, role, content) VALUES (?, ?, ?, ?)
                    """, userId, sessionId, role, role + " 历史消息 " + i);
        }
    }

    private void awaitMemoryIdle() {
        for (int i = 0; i < 50; i++) {
            if (memoryExecutor.getActiveCount() == 0 && memoryExecutor.getThreadPoolExecutor().getQueue().isEmpty()) {
                return;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static ChatEventSink noopSink() {
        return new ChatEventSink() {
        };
    }

    private static Path createTempDir() {
        try {
            return Files.createTempDirectory("m9-memory-integration-");
        } catch (Exception e) {
            throw new IllegalStateException("创建 M9 集成测试临时目录失败", e);
        }
    }
}

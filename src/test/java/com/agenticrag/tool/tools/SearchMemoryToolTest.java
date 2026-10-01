package com.agenticrag.tool.tools;

import com.agenticrag.memory.MemoryProperties;
import com.agenticrag.memory.MemoryScope;
import com.agenticrag.memory.longterm.LongTermMemoryService;
import com.agenticrag.tool.ToolRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SearchMemoryTool 单测（M9/T9）：
 * ①query 缺失返回错误提示；②无 ThreadLocal 上下文时友好降级（N1，多 Agent 子任务场景）；
 * ③正常路径按当前线程用户检索（N3 不越权）；④空结果渲染；⑤register 自动登记。
 */
class SearchMemoryToolTest {

    private LongTermMemoryService longTermMemoryService;
    private ToolRegistry toolRegistry;
    private SearchMemoryTool tool;

    @BeforeEach
    void setUp() {
        longTermMemoryService = mock(LongTermMemoryService.class);
        toolRegistry = mock(ToolRegistry.class);
        MemoryProperties properties = new MemoryProperties();
        properties.setLtmSearchTopk(3);
        tool = new SearchMemoryTool(longTermMemoryService, properties, toolRegistry);
    }

    @AfterEach
    void tearDown() {
        MemoryScope.clear();
    }

    @Test
    void 元信息_名称为search_memory() {
        assertEquals("search_memory", tool.name());
        assertTrue(tool.description().contains("长期记忆"));
        assertTrue(tool.parametersSchema().contains("query"));
    }

    @Test
    void register_向注册中心登记自身() {
        tool.register();
        verify(toolRegistry).register(tool);
    }

    @Test
    void query缺失_返回错误提示且不检索() {
        String result = tool.execute(Map.of());

        assertTrue(result.contains("缺少 query"));
        verify(longTermMemoryService, never()).search(anyLong(), anyString(), anyInt());
    }

    @Test
    void 无会话上下文_返回降级提示且不检索() {
        // 不 set MemoryScope：模拟多 Agent 子任务等非请求线程
        String result = tool.execute(Map.of("query", "用户的城市偏好"));

        assertTrue(result.contains("无会话上下文"));
        verify(longTermMemoryService, never()).search(anyLong(), anyString(), anyInt());
    }

    @Test
    void 正常路径_按当前线程用户检索() {
        MemoryScope.set(7L, "s1");
        when(longTermMemoryService.search(7L, "用户的城市偏好", 3))
                .thenReturn(List.of("用户定居上海"));

        String result = tool.execute(Map.of("query", "用户的城市偏好"));

        assertTrue(result.contains("检索到 1 条长期记忆"));
        assertTrue(result.contains("[1] 用户定居上海"));
        assertFalse(result.contains("[2]")); // 单条命中不应出现多余编号
    }

    @Test
    void 空结果_返回未检索到提示() {
        MemoryScope.set(7L, "s1");
        when(longTermMemoryService.search(7L, "用户的城市偏好", 3)).thenReturn(List.of());

        String result = tool.execute(Map.of("query", "用户的城市偏好"));

        assertEquals("未检索到相关长期记忆。", result);
    }

    @Test
    void 不同用户上下文_隔离不互相读取() {
        // 用户 7 检索：只允许 search(7L, ...) 语义，其他 userId 参数不应被调用
        MemoryScope.set(7L, "s1");
        when(longTermMemoryService.search(eq(7L), anyString(), anyInt())).thenReturn(List.of());

        tool.execute(Map.of("query", "用户的城市偏好"));

        verify(longTermMemoryService, never()).search(eq(8L), anyString(), anyInt());
    }
}

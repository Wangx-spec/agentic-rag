package com.agenticrag.api;

import com.agenticrag.memory.entity.EntityMemoryService;
import com.agenticrag.memory.longterm.LongTermMemoryRepository;
import com.agenticrag.memory.longterm.LongTermMemoryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MemoryAdminController 单测（M9/T12，AC8）：四端点路由与 userId 透传；
 * 注意 @WebMvcTest 下 @ConditionalOnProperty 需显式开启 rag.memory.type=jdbc。
 */
@WebMvcTest(MemoryAdminController.class)
class MemoryAdminControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private EntityMemoryService entityMemoryService;

    @MockBean
    private LongTermMemoryService longTermMemoryService;

    @Test
    void getProfile返回结构化条目() throws Exception {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("所在地", "上海");
        when(entityMemoryService.loadEntries(7L)).thenReturn(entries);

        mockMvc.perform(get("/api/memory/profile/7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.所在地").value("上海"));
    }

    @Test
    void deleteProfile委托清除() throws Exception {
        mockMvc.perform(delete("/api/memory/profile/7"))
                .andExpect(status().isNoContent());

        verify(entityMemoryService).clear(7L);
    }

    @Test
    void getLongTerm返回最近优先列表() throws Exception {
        when(longTermMemoryService.listAll(7L)).thenReturn(List.of(
                new LongTermMemoryRepository.MemoryEntry(12L, "用户定居上海", "s1",
                        Timestamp.valueOf("2026-09-29 10:00:00"))
        ));

        mockMvc.perform(get("/api/memory/longterm/7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].content").value("用户定居上海"))
                .andExpect(jsonPath("$[0].sourceSessionId").value("s1"));
    }

    @Test
    void deleteLongTerm委托清除() throws Exception {
        mockMvc.perform(delete("/api/memory/longterm/7"))
                .andExpect(status().isNoContent());

        verify(longTermMemoryService).clearAll(7L);
    }
}

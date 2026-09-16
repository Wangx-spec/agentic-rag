package com.agenticrag.api;

import com.agenticrag.rag.retrieve.RetrievedChunk;
import com.agenticrag.service.ChatEventSink;
import com.agenticrag.service.ChatMode;
import com.agenticrag.service.ChatResult;
import com.agenticrag.service.ChatService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({ChatController.class, HealthController.class})
class ChatControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ChatService chatService;

    @Test
    void chatStreamsEventsFromService() throws Exception {
        doAnswer(invocation -> {
            ChatEventSink sink = invocation.getArgument(3);
            sink.onThinking("先检索知识库，再组织答案。");
            sink.onDelta("RAG answers with citations [1].");
            sink.onDone(List.of(new RetrievedChunk(1L, 10L, 1, "RAG uses vector retrieval.", "guide.pdf", 1.0, 1)));
            return new ChatResult("RAG answers with citations [1].", List.of(), ChatMode.RAG, null, List.of(), false);
        }).when(chatService).chat(eq("s1"), eq("什么是 RAG？"), eq(ChatMode.RAG), any(ChatEventSink.class));

        MvcResult result = mockMvc.perform(post("/api/chat")
                        .contentType("application/json")
                        .content("""
                                {"sessionId":"s1","message":"什么是 RAG？","mode":"rag"}
                                """))
                .andExpect(request().asyncStarted())
                .andReturn();

        result.getAsyncResult(3000);

        String body = mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        org.junit.jupiter.api.Assertions.assertTrue(body.contains("event:thinking"));
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("RAG answers with citations [1]."));
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("guide.pdf"));
    }

    @Test
    void chatReturnsErrorWhenMessageBlank() throws Exception {
        String body = mockMvc.perform(post("/api/chat")
                        .contentType("application/json")
                        .content("""
                                {"sessionId":"s1","message":"   ","mode":"rag"}
                                """))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        org.junit.jupiter.api.Assertions.assertTrue(body.contains("event:error"));
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("message 不能为空"));
    }

    @Test
    void clearMemoryDeletesSessionHistory() throws Exception {
        mockMvc.perform(delete("/api/memory/s1"))
                .andExpect(status().isNoContent());

        verify(chatService).clearMemory("s1");
    }

    @Test
    void healthEndpointReportsUp() throws Exception {
        String body = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/health"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        org.junit.jupiter.api.Assertions.assertTrue(body.contains("\"status\":\"UP\""));
    }
}

package com.agenticrag.tool.tools;

import com.agenticrag.config.RagProperties;
import com.agenticrag.rag.dto.Chunk;
import com.agenticrag.rag.dto.Document;
import com.agenticrag.rag.dto.DocumentStatus;
import com.agenticrag.rag.ingest.DocumentRepository;
import com.agenticrag.tool.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GetDocumentToolTest {

    private DocumentRepository documentRepository;
    private ToolRegistry toolRegistry;
    private RagProperties ragProperties;
    private GetDocumentTool tool;

    @BeforeEach
    void setUp() {
        documentRepository = mock(DocumentRepository.class);
        toolRegistry = mock(ToolRegistry.class);
        ragProperties = new RagProperties();
        ragProperties.getAgent().setDocumentMaxChars(50); // 小上限便于测截断
        tool = new GetDocumentTool(documentRepository, toolRegistry, ragProperties);
    }

    private Document doneDoc(long id, String name, int chunkCount) {
        return new Document(id, name, "/tmp/" + name, chunkCount, DocumentStatus.DONE, null, Instant.now());
    }

    @Test
    void registerSelfOnInit() {
        tool.register();
        verify(toolRegistry).register(tool);
    }

    @Test
    void returnsMetadataAndBodyForExistingDocument() {
        when(documentRepository.findById(1L)).thenReturn(doneDoc(1L, "员工手册.md", 2));
        when(documentRepository.findChunksByDocumentId(1L)).thenReturn(List.of(
                new Chunk(11L, 1L, 0, "第一段内容"),
                new Chunk(12L, 1L, 1, "第二段内容")
        ));

        String result = tool.execute(Map.of("document_id", 1));

        assertTrue(result.contains("文档 ID：1"));
        assertTrue(result.contains("标题：员工手册.md"));
        assertTrue(result.contains("片段数：2"));
        assertTrue(result.contains("第一段内容"));
    }

    @Test
    void truncatesLongBodyAndMarksTotalChars() {
        String longContent = "字".repeat(500);
        when(documentRepository.findById(2L)).thenReturn(doneDoc(2L, "长文档.md", 1));
        when(documentRepository.findChunksByDocumentId(2L)).thenReturn(List.of(
                new Chunk(21L, 2L, 0, longContent)
        ));

        String result = tool.execute(Map.of("document_id", 2));

        assertTrue(result.contains("已截断"));
        assertTrue(result.contains("完整内容共 500 字符"));
        assertFalse(result.contains(longContent)); // 未完整包含原文
    }

    @Test
    void returnsNotFoundForMissingDocument() {
        when(documentRepository.findById(99L)).thenReturn(null);

        String result = tool.execute(Map.of("document_id", 99));

        assertTrue(result.contains("未找到文档 ID=99"));
        assertTrue(result.contains("list_documents"));
    }

    @Test
    void returnsReadableErrorForMissingParam() {
        String result = tool.execute(Map.of());
        assertTrue(result.contains("缺少 document_id 参数"));
    }

    @Test
    void returnsReadableErrorForNonNumericParam() {
        String result = tool.execute(Map.of("document_id", "abc"));
        assertTrue(result.contains("document_id 必须是数字"));
    }

    @Test
    void returnsNotReadyHintForNonDoneDocument() {
        when(documentRepository.findById(3L)).thenReturn(
                new Document(3L, "处理中.md", "/tmp/处理中.md", 0, DocumentStatus.PROCESSING, null, Instant.now()));

        String result = tool.execute(Map.of("document_id", 3));

        assertTrue(result.contains("尚未完成入库"));
    }

    @Test
    void failsOpenWhenRepositoryThrows() {
        when(documentRepository.findById(1L)).thenThrow(new RuntimeException("db down"));

        String result = tool.execute(Map.of("document_id", 1));

        assertTrue(result.contains("读取文档失败"));
    }
}

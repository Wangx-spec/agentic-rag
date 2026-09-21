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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ListDocumentsToolTest {

    private DocumentRepository documentRepository;
    private ToolRegistry toolRegistry;
    private RagProperties ragProperties;
    private ListDocumentsTool tool;

    @BeforeEach
    void setUp() {
        documentRepository = mock(DocumentRepository.class);
        toolRegistry = mock(ToolRegistry.class);
        ragProperties = new RagProperties();
        ragProperties.getAgent().setListDocumentsLimit(3); // 小上限便于测限量
        tool = new ListDocumentsTool(documentRepository, toolRegistry, ragProperties);
    }

    private Document doneDoc(long id, String name) {
        return new Document(id, name, "/tmp/" + name, 5, DocumentStatus.DONE, null, Instant.now());
    }

    private List<Document> doneDocs(int n) {
        List<Document> docs = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            docs.add(doneDoc(i, "文档" + i + ".md"));
        }
        return docs;
    }

    @Test
    void registerSelfOnInit() {
        tool.register();
        verify(toolRegistry).register(tool);
    }

    @Test
    void listsDocumentsWithIdTitleChunkCountAndSummary() {
        when(documentRepository.findAll()).thenReturn(List.of(doneDoc(1L, "员工手册.md")));
        when(documentRepository.findChunksByDocumentId(1L)).thenReturn(List.of(
                new Chunk(11L, 1L, 0, "本手册规定了请假流程")
        ));

        String result = tool.execute(Map.of());

        assertTrue(result.contains("[1] 员工手册.md"));
        assertTrue(result.contains("5 个片段"));
        assertTrue(result.contains("本手册规定了请假流程"));
    }

    @Test
    void appliesDefaultLimitWhenNotProvided() {
        when(documentRepository.findAll()).thenReturn(doneDocs(10));
        when(documentRepository.findChunksByDocumentId(org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(List.of());

        String result = tool.execute(Map.of());

        assertTrue(result.contains("显示前 3 篇"));
        assertTrue(result.contains("[3]"));
        assertFalse(result.contains("[4]"));
    }

    @Test
    void honorsExplicitLimitAndCapsAtConfiguredMax() {
        when(documentRepository.findAll()).thenReturn(doneDocs(10));
        when(documentRepository.findChunksByDocumentId(org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(List.of());

        String smaller = tool.execute(Map.of("limit", 2));
        assertTrue(smaller.contains("显示前 2 篇"));

        String overMax = tool.execute(Map.of("limit", 99));
        assertTrue(overMax.contains("显示前 3 篇")); // 被配置上限截断
    }

    @Test
    void filtersOutNonDoneDocuments() {
        List<Document> docs = new ArrayList<>();
        docs.add(doneDoc(1L, "完成.md"));
        docs.add(new Document(2L, "待处理.md", "/tmp/x", 0, DocumentStatus.PENDING, null, Instant.now()));
        docs.add(new Document(3L, "失败.md", "/tmp/y", 0, DocumentStatus.FAILED, "err", Instant.now()));
        when(documentRepository.findAll()).thenReturn(docs);
        when(documentRepository.findChunksByDocumentId(1L)).thenReturn(List.of());

        String result = tool.execute(Map.of());

        assertTrue(result.contains("共 1 篇文档"));
        assertFalse(result.contains("待处理.md"));
        assertFalse(result.contains("失败.md"));
    }

    @Test
    void returnsEmptyHintWhenNoDoneDocuments() {
        when(documentRepository.findAll()).thenReturn(List.of());

        String result = tool.execute(Map.of());

        assertEquals("知识库暂无已入库文档。", result);
    }

    @Test
    void failsOpenWhenRepositoryThrows() {
        when(documentRepository.findAll()).thenThrow(new RuntimeException("db down"));

        String result = tool.execute(Map.of());

        assertTrue(result.contains("列出文档失败"));
    }
}

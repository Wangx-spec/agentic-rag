package com.agenticrag.rag;

import com.agenticrag.AgenticRagApplication;
import com.agenticrag.rag.dto.Document;
import com.agenticrag.rag.dto.DocumentStatus;
import com.agenticrag.rag.index.EmbeddingClient;
import com.agenticrag.rag.ingest.IngestService;
import com.agenticrag.rag.retrieve.HybridRetriever;
import com.agenticrag.rag.retrieve.RetrievedChunk;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@SpringBootTest(classes = AgenticRagApplication.class)
@Sql(scripts = "classpath:db/init-h2.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class RagSmokeTest {

    static final Path DATA_DIR = createTempDir();

    @Autowired
    private IngestService ingestService;

    @Autowired
    private HybridRetriever hybridRetriever;

    @MockBean
    private EmbeddingClient embeddingClient;

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:rag_smoke;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        registry.add("spring.datasource.username", () -> "sa");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.datasource.driver-class-name", () -> "org.h2.Driver");
        registry.add("rag.vector.type", () -> "memory");
        registry.add("rag.data-dir", () -> DATA_DIR.toString());
    }

    @Test
    void ingestAndRetrieveWorkAcrossPdfPgBm25AndMemoryVectorStore() throws Exception {
        when(embeddingClient.embedBatch(anyList())).thenAnswer(invocation -> {
            List<String> inputs = invocation.getArgument(0);
            return inputs.stream().map(this::toVector).toList();
        });
        when(embeddingClient.embed(anyString())).thenAnswer(invocation -> toVector(invocation.getArgument(0)));

        Document document = ingestService.ingest("guide.pdf",
                new ByteArrayInputStream(createPdf("""
                        RAG uses vector retrieval and BM25 together.
                        Citations help answers stay grounded.
                        """)));

        List<Document> documents = ingestService.listDocuments();
        List<RetrievedChunk> retrieved = hybridRetriever.retrieve("vector retrieval");

        assertEquals(DocumentStatus.READY, document.status());
        assertTrue(document.chunkCount() > 0);
        assertEquals(1, documents.size());
        assertEquals("guide.pdf", documents.get(0).name());
        assertFalse(retrieved.isEmpty());
        assertEquals("guide.pdf", retrieved.get(0).docName());
        assertTrue(retrieved.get(0).content().contains("vector retrieval"));
    }

    private float[] toVector(String text) {
        String normalized = text == null ? "" : text.toLowerCase();
        if (normalized.contains("vector") || normalized.contains("bm25") || normalized.contains("rag")) {
            return new float[]{1.0f, 0.0f};
        }
        return new float[]{0.0f, 1.0f};
    }

    private static Path createTempDir() {
        try {
            return Files.createTempDirectory("rag-smoke-");
        } catch (Exception e) {
            throw new IllegalStateException("创建 smoke test 临时目录失败", e);
        }
    }

    private byte[] createPdf(String content) throws Exception {
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            try (PDPageContentStream stream = new PDPageContentStream(document, document.getPage(0))) {
                stream.beginText();
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                stream.newLineAtOffset(72, 720);
                for (String line : content.split("\n")) {
                    stream.showText(line);
                    stream.newLineAtOffset(0, -16);
                }
                stream.endText();
            }
            document.save(output);
            return output.toByteArray();
        }
    }
}

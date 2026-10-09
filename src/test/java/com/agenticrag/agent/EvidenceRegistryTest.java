package com.agenticrag.agent;

import com.agenticrag.rag.retrieve.RetrievedChunk;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvidenceRegistryTest {

    @Test
    void documentEvidenceUsesChunkRank() {
        EvidenceRegistry registry = new EvidenceRegistry();

        registry.registerDocumentChunks(List.of(
                new RetrievedChunk(10L, 1L, 1, "内容", "doc.md", 0.9, 3)
        ));

        EvidenceRegistry.Evidence evidence = registry.find("3").orElseThrow();
        assertEquals(EvidenceRegistry.EvidenceType.DOCUMENT_CHUNK, evidence.type());
        assertEquals("10", evidence.payloadRef());
    }

    @Test
    void sqlEvidenceContinuesAfterDocumentRank() {
        EvidenceRegistry registry = new EvidenceRegistry();
        registry.registerDocumentChunks(List.of(
                new RetrievedChunk(10L, 1L, 1, "内容", "doc.md", 0.9, 3)
        ));

        EvidenceRegistry.Evidence sql = registry.registerSqlResult(
                "SELECT COUNT(*) FROM orders",
                List.of("cnt"),
                List.of(List.of(12)),
                false
        );

        assertEquals("4", sql.id());
        assertTrue(sql.citationText().contains("证据 [4]"));
    }

    @Test
    void sqlEvidenceSummaryContainsColumnsAndFirstRowValues() {
        EvidenceRegistry registry = new EvidenceRegistry();

        EvidenceRegistry.Evidence sql = registry.registerSqlResult(
                "SELECT platform, SUM(amount) AS total_amount FROM orders GROUP BY platform",
                List.of("platform", "total_amount"),
                List.of(List.of("淘宝", 1288.5)),
                true
        );

        assertTrue(sql.summary().contains("SELECT platform"));
        assertTrue(sql.summary().contains("platform"));
        assertTrue(sql.summary().contains("total_amount"));
        assertTrue(sql.summary().contains("淘宝"));
        assertTrue(sql.summary().contains("1288.5"));
        assertTrue(sql.summary().contains("已截断"));
    }

    @Test
    void duplicateDocumentRankGetsNewId() {
        EvidenceRegistry registry = new EvidenceRegistry();
        registry.registerDocumentChunks(List.of(
                new RetrievedChunk(10L, 1L, 1, "内容1", "a.md", 0.9, 1),
                new RetrievedChunk(11L, 2L, 1, "内容2", "b.md", 0.8, 1)
        ));

        assertEquals(2, registry.snapshot().size());
        assertTrue(registry.find("1").isPresent());
        assertTrue(registry.find("2").isPresent());
    }
}

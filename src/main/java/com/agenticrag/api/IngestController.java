package com.agenticrag.api;

import com.agenticrag.rag.dto.Chunk;
import com.agenticrag.rag.dto.Document;
import com.agenticrag.rag.ingest.IngestService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * 文档入库接口
 * <p>
 * 计划端点：
 * - POST /api/ingest     multipart 上传文档（pdf/txt/md），走 IngestService 完整入库，返回 {documentId, name, chunkCount}
 * - GET  /api/documents  文档列表（id/name/chunkCount/status）
 */
@RestController
@RequestMapping("/api")
public class IngestController {
    private final IngestService ingestService;

    public IngestController(IngestService ingestService) {
        this.ingestService = ingestService;
    }

    @PostMapping(value = "/ingest", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> ingest(@RequestParam("file") MultipartFile file) throws Exception {
        Document document = ingestService.ingest(file.getOriginalFilename(), file.getInputStream());
        return Map.of(
                "documentId", document.id(),
                "name", document.name(),
                "chunkCount", document.chunkCount(),
                "status", document.status().name()
        );
    }

    @GetMapping("/documents")
    public List<Map<String, Object>> documents() {
        return ingestService.listDocuments().stream()
                .map(document -> Map.<String, Object>of(
                        "documentId", document.id(),
                        "name", document.name(),
                        "chunkCount", document.chunkCount(),
                        "status", document.status().name(),
                        "createdAt", document.createdAt().toString()
                ))
                .toList();
    }

    @GetMapping("/documents/{id}/chunks")
    public List<Map<String, Object>> chunks(@PathVariable Long id) {
        return ingestService.listChunks(id).stream()
                .map(chunk -> Map.<String, Object>of(
                        "seq", chunk.seq(),
                        "content", chunk.content()
                ))
                .toList();
    }

    /** 删除文档（PG/Qdrant/FTS 三处联动），幂等：不存在也返回 204 */
    @DeleteMapping("/documents/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        ingestService.deleteDocument(id);
        return ResponseEntity.noContent().build();
    }
}

package com.agenticrag.api;

import com.agenticrag.config.RagProperties;
import com.agenticrag.rag.dto.Chunk;
import com.agenticrag.rag.dto.Document;
import com.agenticrag.rag.ingest.IngestService;
import com.agenticrag.rag.ingest.IngestTaskQueue;
import org.springframework.http.HttpStatus;
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
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;


@RestController
@RequestMapping("/api")
public class IngestController {
    private final IngestService ingestService;
    private final IngestTaskQueue ingestTaskQueue;
    private final RagProperties ragProperties;

    public IngestController(IngestService ingestService, IngestTaskQueue ingestTaskQueue, RagProperties ragProperties) {
        this.ingestService = ingestService;
        this.ingestTaskQueue = ingestTaskQueue;
        this.ragProperties = ragProperties;
    }

    @PostMapping(value = "/ingest", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> ingest(@RequestParam("file") MultipartFile file) throws Exception {
        validateFile(file);
        Document document = ingestService.submitTask(file.getOriginalFilename(), file.getInputStream());
        ingestTaskQueue.submit(document.id());
        return Map.of(
                "documentId", document.id(),
                "name", document.name(),
                "status", document.status().name()
        );
    }

    @GetMapping("/documents")
    public List<Map<String, Object>> documents() {
        return ingestService.listDocuments().stream()
                .map(document -> Map.<String, Object>of(
                        "documentId", document.id(),
                        "name", document.name(),
                        "filePath", document.filePath() != null ? document.filePath() : "",
                        "chunkCount", document.chunkCount(),
                        "status", document.status().name(),
                        "errorMsg", document.errorMsg() != null ? document.errorMsg() : "",
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

    @DeleteMapping("/documents/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        ingestService.deleteDocument(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * 验证文件是否符合要求
     * 检查文件名、大小、扩展名是否符合配置
     *
     * @param file
     */
    private void validateFile(MultipartFile file){

        // 检查文件名是否为空
        String fileName = file.getOriginalFilename();
        if (fileName == null || fileName.isBlank()){
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文件名不能为空");
        }

        // 检查文件大小是否超过限制
        long maxSize = ragProperties.getMaxFileSizeBytes();
        if (file.getSize() > maxSize){
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "文件大小超过限制，最大支持 " + (maxSize / 1024 / 1024) + "MB");
        }

        // 允许的文件扩展名
        Set<String> allowed = Arrays.stream(ragProperties.getAllowedExtensions().split(","))
            .map(String::trim)
            .map(String::toLowerCase)
            .collect(Collectors.toSet());
        
        // 检查文件扩展名是否在允许列表中
        String ext = "";
        int lastDot = fileName.lastIndexOf('.');
        if (lastDot >= 0) {
            ext = fileName.substring(lastDot).toLowerCase();
        }

        if (!allowed.contains(ext)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "不支持的文件类型: " + ext + "，仅支持 " + ragProperties.getAllowedExtensions());
        }

    }
}

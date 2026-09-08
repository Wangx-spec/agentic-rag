package com.agenticrag.rag.ingest;

import com.agenticrag.rag.dto.Chunk;
import com.agenticrag.rag.dto.Document;
import com.agenticrag.rag.dto.DocumentStatus;
import com.agenticrag.rag.index.Bm25Store;
import com.agenticrag.rag.index.EmbeddingClient;
import com.agenticrag.rag.index.VectorStore;
import com.agenticrag.rag.storage.StorageClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
public class IngestService {

    private static final Logger logger = LoggerFactory.getLogger(IngestService.class);

    private final JdbcTemplate jdbcTemplate;
    private final DocumentRepository documentRepository;
    private final DocumentParserSelector parserSelector;
    private final Chunker chunker;
    private final StorageClient storageClient;
    private final EmbeddingClient embeddingClient;
    private final VectorStore vectorStore;
    private final Bm25Store bm25Store;

    public IngestService(JdbcTemplate jdbcTemplate,
                         DocumentRepository documentRepository,
                         DocumentParserSelector parserSelector,
                         Chunker chunker,
                         EmbeddingClient embeddingClient,
                         VectorStore vectorStore,
                         Bm25Store bm25Store,
                         StorageClient storageClient) {
        this.jdbcTemplate = jdbcTemplate;
        this.documentRepository = documentRepository;
        this.parserSelector = parserSelector;
        this.chunker = chunker;
        this.embeddingClient = embeddingClient;
        this.vectorStore = vectorStore;
        this.bm25Store = bm25Store;
        this.storageClient = storageClient;
    }

    @Transactional
    public Document submitTask(String filename, InputStream in) {
        String filePath = null;
        try {
            filePath = storageClient.save(filename, in);
        } catch (Exception e) {
            throw new IllegalStateException("文件保存失败: " + filename, e);
        }

        Long documentId = documentRepository.insertPending(filename, filePath);
        Document doc = documentRepository.findById(documentId);
        if (doc == null) {
            throw new IllegalStateException("插入文档后无法回读: " + filename);
        }
        return doc;
    }

    @Transactional
    public void processDocument(Long documentId) {
        Document doc = documentRepository.findById(documentId);
        if (doc == null || doc.status() == DocumentStatus.DONE){
            return;
        }
        if (!documentRepository.markProcessingIfPending(documentId)) {
            logger.info("跳过文档处理，任务已被其他流程接管或状态已变更: id={}, status={}", documentId, doc.status());
            return;
        }
        doc = documentRepository.findById(documentId);
        if (doc == null) {
            return;
        }

        try {
            InputStream in = storageClient.get(doc.filePath());
            String text = parserSelector.select(doc.name()).parse(in);
            List<Chunk> chunks = chunker.split(text, doc.name());

            List<Chunk> savedChunks = insertChunks(documentId, chunks);

            List<String> contents = savedChunks.stream().map(Chunk::content).toList();
            List<float[]> vectors = embeddingClient.embedBatch(contents);

            vectorStore.saveChunks(documentId, doc.name(), savedChunks, vectors);
            bm25Store.saveChunks(doc.name(), savedChunks);

            documentRepository.markDone(documentId, savedChunks.size());
            logger.info("文档处理完成: {} (id={}, chunks={})", doc.name(), documentId, savedChunks.size());
        } catch (Exception e) {
            logger.error("文档处理失败: {} (id={})", doc.name(), documentId, e);
            String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            documentRepository.markFailed(documentId, errorMsg);
        }
    }

    public List<Document> listDocuments() {
        return documentRepository.findAll();
    }

    public List<Chunk> listChunks(Long documentId) {
        return documentRepository.findChunksByDocumentId(documentId);
    }


    public void deleteDocument(Long documentId) {
        Document doc = documentRepository.findById(documentId);
        vectorStore.deleteByDocumentId(documentId);
        bm25Store.deleteByDocumentId(documentId);
        documentRepository.delete(documentId);
        if (doc != null && doc.filePath() != null){
            try {
                storageClient.delete(doc.filePath());
            } catch (Exception e){
                logger.warn("删除原始文件失败 (id={}, path={}): {}", documentId, doc.filePath(), e.getMessage());
            }
        }
    }

    private List<Chunk> insertChunks(Long documentId, List<Chunk> chunks) {
        List<Chunk> saved = new ArrayList<>();
        for (Chunk chunk : chunks) {
            KeyHolder keyHolder = new GeneratedKeyHolder();
            jdbcTemplate.update(connection -> {
                PreparedStatement ps = connection.prepareStatement(
                        "INSERT INTO chunks(document_id, seq, content) VALUES (?, ?, ?)",
                        new String[]{"id"}
                );
                ps.setLong(1, documentId);
                ps.setInt(2, chunk.seq());
                ps.setString(3, chunk.content());
                return ps;
            }, keyHolder);
            Number key = keyHolder.getKey();
            if (key == null) {
                throw new IllegalStateException("插入 chunks 未返回主键");
            }
            saved.add(new Chunk(key.longValue(), documentId, chunk.seq(), chunk.content()));
        }
        return saved;
    }
}

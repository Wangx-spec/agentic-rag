package com.agenticrag.rag.ingest;

import com.agenticrag.rag.dto.Chunk;
import com.agenticrag.rag.dto.Document;
import com.agenticrag.rag.dto.DocumentStatus;
import com.agenticrag.rag.index.Bm25Store;
import com.agenticrag.rag.index.EmbeddingClient;
import com.agenticrag.rag.index.VectorStore;
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

    private final JdbcTemplate jdbcTemplate;
    private final DocumentRepository documentRepository;
    private final DocumentParserSelector parserSelector;
    private final Chunker chunker;
    private final EmbeddingClient embeddingClient;
    private final VectorStore vectorStore;
    private final Bm25Store bm25Store;

    public IngestService(JdbcTemplate jdbcTemplate,
                         DocumentRepository documentRepository,
                         DocumentParserSelector parserSelector,
                         Chunker chunker,
                         EmbeddingClient embeddingClient,
                         VectorStore vectorStore,
                         Bm25Store bm25Store) {
        this.jdbcTemplate = jdbcTemplate;
        this.documentRepository = documentRepository;
        this.parserSelector = parserSelector;
        this.chunker = chunker;
        this.embeddingClient = embeddingClient;
        this.vectorStore = vectorStore;
        this.bm25Store = bm25Store;
    }

    @Transactional
    public Document ingest(String filename, InputStream in) {
        Long documentId = documentRepository.insertProcessing(filename);

        try {
            String text = parserSelector.select(filename).parse(in);
            List<Chunk> chunks = chunker.split(text, filename);
            List<Chunk> savedChunks = insertChunks(documentId, chunks);

            List<String> contents = savedChunks.stream().map(Chunk::content).toList();
            List<float[]> vectors = embeddingClient.embedBatch(contents);

            vectorStore.saveChunks(documentId, filename, savedChunks, vectors);
            bm25Store.saveChunks(filename, savedChunks);

            documentRepository.markReady(documentId, savedChunks.size());
            return new Document(documentId, filename, savedChunks.size(), DocumentStatus.READY, Instant.now());
        } catch (Exception e) {
            documentRepository.markFailed(documentId);
            throw new IllegalStateException("文档入库失败: " + filename, e);
        }
    }

    public List<Document> listDocuments() {
        return documentRepository.findAll();
    }

    public List<Chunk> listChunks(Long documentId) {
        return documentRepository.findChunksByDocumentId(documentId);
    }

    /**
     * 删除文档：三处联动清理。先清检索侧（Qdrant/FTS），再清 PG 元数据（事实源放最后，失败可重试，均幂等）。
     */
    public void deleteDocument(Long documentId) {
        vectorStore.deleteByDocumentId(documentId);
        bm25Store.deleteByDocumentId(documentId);
        documentRepository.delete(documentId);
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

package com.agenticrag.rag.ingest;

import com.agenticrag.rag.dto.Chunk;
import com.agenticrag.rag.dto.Document;
import com.agenticrag.rag.dto.DocumentStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

@Repository
public class DocumentRepository {
    private final JdbcTemplate jdbcTemplate;

    private final RowMapper<Document> rowMapper = (rs, rowNum) -> new Document(
            rs.getLong("id"),
            rs.getString("name"),
            rs.getInt("chunk_count"),
            DocumentStatus.valueOf(rs.getString("status")),
            rs.getTimestamp("created_at").toInstant()
    );

    public DocumentRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Long insertProcessing(String name) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO documents(name, chunk_count, status, created_at) VALUES (?, ?, ?, ?)",
                    new String[]{"id"}
            );
            ps.setString(1, name);
            ps.setInt(2, 0);
            ps.setString(3, DocumentStatus.PROCESSING.name());
            ps.setTimestamp(4, Timestamp.from(Instant.now()));
            return ps;
        }, keyHolder);

        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("插入 documents 未返回主键");
        }
        return key.longValue();
    }

    public void markReady(Long documentId, int chunkCount) {
        jdbcTemplate.update(
                "UPDATE documents SET chunk_count = ?, status = ? WHERE id = ?",
                chunkCount,
                DocumentStatus.READY.name(),
                documentId
        );
    }

    public void markFailed(Long documentId) {
        jdbcTemplate.update(
                "UPDATE documents SET status = ? WHERE id = ?",
                DocumentStatus.FAILED.name(),
                documentId
        );
    }

    public List<Document> findAll() {
        return jdbcTemplate.query(
                "SELECT id, name, chunk_count, status, created_at FROM documents ORDER BY created_at DESC",
                rowMapper
        );
    }

    public List<Chunk> findChunksByDocumentId(Long documentId) {
        return jdbcTemplate.query(
                "SELECT id, document_id, seq, content FROM chunks WHERE document_id = ? ORDER BY seq",
                (rs, rowNum) -> new Chunk(
                        rs.getLong("id"),
                        rs.getLong("document_id"),
                        rs.getInt("seq"),
                        rs.getString("content")
                ),
                documentId
        );
    }

    /** 删除文档元数据（chunks 由 ON DELETE CASCADE 级联删除），幂等 */
    public void delete(Long documentId) {
        jdbcTemplate.update("DELETE FROM documents WHERE id = ?", documentId);
    }
}

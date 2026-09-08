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
            rs.getString("file_path"),
            rs.getInt("chunk_count"),
            DocumentStatus.valueOf(rs.getString("status")),
            rs.getString("error_msg"),
            rs.getTimestamp("created_at").toInstant()
    );

    public DocumentRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 插入待处理文档（PENDING 状态）
     */
    public Long insertPending(String name, String filePath) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO documents(name, file_path, chunk_count, status, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)",
                    new String[]{"id"}
            );
            ps.setString(1, name);
            ps.setString(2, filePath);
            ps.setInt(3, 0);
            ps.setString(4, DocumentStatus.PENDING.name());
            ps.setTimestamp(5, Timestamp.from(Instant.now()));
            ps.setTimestamp(6, Timestamp.from(Instant.now()));
            return ps;
        }, keyHolder);

        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("插入 documents 未返回主键");
        }
        return key.longValue();
    }

    /**
     * 标记文档为处理中状态
     */
    public void markProcessing(Long documentId) {
        jdbcTemplate.update(
                "UPDATE documents SET status = ?, updated_at = ? WHERE id = ?",
                DocumentStatus.PROCESSING.name(),
                Timestamp.from(Instant.now()),
                documentId
        );
    }

    /**
     * 仅当文档仍处于 PENDING 时才抢占为 PROCESSING。
     *
     * @return true 表示当前调用成功抢到任务；false 表示任务已被其他流程处理或状态已变更
     */
    public boolean markProcessingIfPending(Long documentId) {
        int updated = jdbcTemplate.update(
                "UPDATE documents SET status = ?, updated_at = ? WHERE id = ? AND status = ?",
                DocumentStatus.PROCESSING.name(),
                Timestamp.from(Instant.now()),
                documentId,
                DocumentStatus.PENDING.name()
        );
        return updated > 0;
    }

    /**
     * 标记文档为完成状态（原 markReady，现改为 DONE）
     */
    public void markDone(Long documentId, int chunkCount) {
        jdbcTemplate.update(
                "UPDATE documents SET chunk_count = ?, status = ?, updated_at = ? WHERE id = ?",
                chunkCount,
                DocumentStatus.DONE.name(),
                Timestamp.from(Instant.now()),
                documentId
        );
    }

    /**
     * 标记文档为失败状态，并记录错误信息
     */
    public void markFailed(Long documentId, String errorMsg) {
        jdbcTemplate.update(
                "UPDATE documents SET status = ?, error_msg = ?, updated_at = ? WHERE id = ?",
                DocumentStatus.FAILED.name(),
                errorMsg,
                Timestamp.from(Instant.now()),
                documentId
        );
    }
    /**
     * 根据ID查找文档
     * @return 文档对象或 null（若不存在）
     */
    public Document findById(Long documentId){
        List<Document> results = jdbcTemplate.query(
            "SELECT id, name, file_path, chunk_count, status, error_msg, created_at FROM documents WHERE id = ?",
            rowMapper,
            documentId
        );
        return results.isEmpty() ? null : results.get(0);
    }

    /**
     * 查找所有待处理或处理中的文档（用于启动恢复）
     * @return 文档列表（按创建时间降序）
     */
    public List<Document> findPendingOrProcessing(){
        return jdbcTemplate.query(
            "SELECT id, name, file_path, chunk_count, status, error_msg, created_at FROM documents " +
            "WHERE status IN (?, ?) ORDER BY created_at ASC",
            rowMapper,
            DocumentStatus.PENDING.name(),
            DocumentStatus.PROCESSING.name()
        );
    }

    /**
     * 启动恢复时，将上次异常中断留下的 PROCESSING 任务回滚为 PENDING。
     *
     * @return 被回滚的任务数
     */
    public int resetProcessingToPending() {
        return jdbcTemplate.update(
                "UPDATE documents SET status = ?, updated_at = ?, error_msg = NULL WHERE status = ?",
                DocumentStatus.PENDING.name(),
                Timestamp.from(Instant.now()),
                DocumentStatus.PROCESSING.name()
        );
    }

    public List<Document> findAll() {
        return jdbcTemplate.query(
                "SELECT id, name, file_path, chunk_count, status, error_msg, created_at FROM documents ORDER BY created_at DESC",
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

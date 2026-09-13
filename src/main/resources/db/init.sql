CREATE TABLE IF NOT EXISTS documents (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(512) NOT NULL,
    chunk_count INT NOT NULL DEFAULT 0,
    status      VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    file_path   VARCHAR(1024),
    error_msg   VARCHAR(1024),
    updated_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

--兼容旧表：补齐新增列
ALTER TABLE documents ADD COLUMN IF NOT EXISTS file_path VARCHAR(1024);

--兼容旧数据：READY 状态重命名为 DONE
UPDATE documents SET status = 'DONE' WHERE status = 'READY';
ALTER TABLE documents ADD COLUMN IF NOT EXISTS error_msg VARCHAR(1024);
ALTER TABLE documents ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE documents ADD COLUMN IF NOT EXISTS status VARCHAR(16) NOT NULL DEFAULT 'PENDING';

CREATE TABLE IF NOT EXISTS chunks (
    id          BIGSERIAL PRIMARY KEY,
    document_id BIGINT NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    seq         INT NOT NULL,
    content     TEXT NOT NULL,
    UNIQUE (document_id, seq)
);

CREATE INDEX IF NOT EXISTS idx_chunks_document_id ON chunks(document_id);
CREATE INDEX IF NOT EXISTS idx_documents_created_at ON documents(created_at DESC);

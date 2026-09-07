CREATE TABLE documents (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(512) NOT NULL,
    chunk_count INT NOT NULL DEFAULT 0,
    status      VARCHAR(16) NOT NULL DEFAULT 'PROCESSING',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE chunks (
    id          BIGSERIAL PRIMARY KEY,
    document_id BIGINT NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    seq         INT NOT NULL,
    content     TEXT NOT NULL,
    UNIQUE (document_id, seq)
);

CREATE INDEX idx_chunks_document_id ON chunks(document_id);
CREATE INDEX idx_documents_created_at ON documents(created_at DESC);

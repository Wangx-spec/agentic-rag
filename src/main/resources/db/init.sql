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

-- ================= M9 记忆持久化（2026-09） =================
-- 短期记忆（F1）：会话消息原文，load 时取最近 N 条
CREATE TABLE IF NOT EXISTS conversation_messages (
    user_id     BIGINT NOT NULL DEFAULT 0,
    session_id  VARCHAR(64) NOT NULL,
    role        VARCHAR(16) NOT NULL,
    content     TEXT NOT NULL,
    created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    id          BIGSERIAL PRIMARY KEY
);
CREATE INDEX IF NOT EXISTS idx_conv_msgs ON conversation_messages(user_id, session_id, id);

-- 会话滑动摘要（F2）：每 (user, session) 一行，滚动更新；last_message_id = 摘要已覆盖到的消息位置（半窗口重叠判定用）
CREATE TABLE IF NOT EXISTS conversation_summaries (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT NOT NULL DEFAULT 0,
    session_id      VARCHAR(64) NOT NULL,
    content         TEXT NOT NULL,
    last_message_id BIGINT NOT NULL,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (user_id, session_id)
);

-- 实体记忆（F3）：每用户一行，profile 存 JSON 字符串（代码仅全量读/写，不做字段级查询）
CREATE TABLE IF NOT EXISTS user_profiles (
    user_id    BIGINT PRIMARY KEY,
    profile    TEXT NOT NULL DEFAULT '{}',
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 长期记忆元数据（F4）：向量存 Qdrant 独立集合 agentic_rag_memories，point id = 本表 id
CREATE TABLE IF NOT EXISTS long_term_memories (
    id                BIGSERIAL PRIMARY KEY,
    user_id           BIGINT NOT NULL DEFAULT 0,
    content           TEXT NOT NULL,
    source_session_id VARCHAR(64),
    created_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_ltm ON long_term_memories(user_id, created_at DESC);

-- ================= S3.3 数据分析评测轨迹（2026-10） =================
-- 仅评测开关开启时写入；主问答链路 fail-open，不依赖本表成功写入。
CREATE TABLE IF NOT EXISTS run_trace (
    id BIGSERIAL PRIMARY KEY,
    run_id TEXT NOT NULL,
    question_id TEXT NOT NULL,
    turn INT NOT NULL,
    event_type TEXT NOT NULL,
    tool_name TEXT,
    tool_args_summary TEXT,
    result_summary TEXT,
    evidence_ids TEXT ARRAY,
    evidence_types TEXT ARRAY,
    routed_intent TEXT,
    critic_verdict TEXT,
    tokens_used INT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_run_trace_run ON run_trace(run_id, question_id);

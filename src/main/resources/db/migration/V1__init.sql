CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS review_task (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT,
    repo_owner VARCHAR(128) NOT NULL,
    repo_name VARCHAR(128) NOT NULL,
    pr_number INT NOT NULL,
    pr_url VARCHAR(512) NOT NULL,
    title VARCHAR(255),
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    total_files INT DEFAULT 0,
    total_issues INT DEFAULT 0,
    risk_level VARCHAR(32),
    error_message TEXT,
    started_at TIMESTAMP,
    finished_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_review_task_status
    ON review_task (status);

CREATE INDEX IF NOT EXISTS idx_review_task_repo_pr
    ON review_task (repo_owner, repo_name, pr_number);

CREATE TABLE IF NOT EXISTS review_file (
    id BIGSERIAL PRIMARY KEY,
    task_id BIGINT NOT NULL,
    file_path VARCHAR(512) NOT NULL,
    change_type VARCHAR(32),
    patch TEXT,
    additions INT DEFAULT 0,
    deletions INT DEFAULT 0,
    skipped BOOLEAN DEFAULT FALSE,
    skip_reason VARCHAR(255),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_review_file_task_id
    ON review_file (task_id);

CREATE TABLE IF NOT EXISTS review_issue (
    id BIGSERIAL PRIMARY KEY,
    task_id BIGINT NOT NULL,
    file_path VARCHAR(512),
    line_number INT,
    issue_type VARCHAR(64),
    severity VARCHAR(32),
    title VARCHAR(255),
    description TEXT,
    suggestion TEXT,
    source VARCHAR(32),
    rule_reference VARCHAR(255),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_review_issue_task_id
    ON review_issue (task_id);

CREATE INDEX IF NOT EXISTS idx_review_issue_severity
    ON review_issue (severity);

CREATE INDEX IF NOT EXISTS idx_review_issue_issue_type
    ON review_issue (issue_type);

CREATE TABLE IF NOT EXISTS llm_call_log (
    id BIGSERIAL PRIMARY KEY,
    task_id BIGINT,
    model_name VARCHAR(128),
    cost_time_ms BIGINT,
    request_summary TEXT,
    response_summary TEXT,
    success BOOLEAN DEFAULT TRUE,
    error_message TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_llm_call_log_task_id
    ON llm_call_log (task_id);

CREATE TABLE IF NOT EXISTS rule_document (
    id BIGSERIAL PRIMARY KEY,
    title VARCHAR(255) NOT NULL,
    type VARCHAR(64),
    source VARCHAR(255),
    content TEXT,
    enabled BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS rule_chunk (
    id BIGSERIAL PRIMARY KEY,
    document_id BIGINT NOT NULL,
    chunk_index INT NOT NULL,
    content TEXT NOT NULL,
    embedding VECTOR,
    type VARCHAR(64),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_rule_chunk_document_id
    ON rule_chunk (document_id);

CREATE INDEX IF NOT EXISTS idx_rule_chunk_embedding
    ON rule_chunk
    USING hnsw (embedding vector_cosine_ops);

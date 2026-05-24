CREATE TABLE IF NOT EXISTS ai_review_cache (
    id BIGSERIAL PRIMARY KEY,
    cache_key VARCHAR(128) NOT NULL,
    provider VARCHAR(64) NOT NULL,
    model_name VARCHAR(128) NOT NULL,
    prompt_signature VARCHAR(128) NOT NULL,
    file_path VARCHAR(512),
    result_json TEXT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    last_used_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_ai_review_cache_key
    ON ai_review_cache (cache_key);

CREATE INDEX IF NOT EXISTS idx_ai_review_cache_last_used_at
    ON ai_review_cache (last_used_at);

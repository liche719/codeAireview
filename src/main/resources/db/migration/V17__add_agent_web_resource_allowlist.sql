CREATE TABLE IF NOT EXISTS agent_web_resource (
    id BIGSERIAL PRIMARY KEY,
    agent_run_id BIGINT NOT NULL,
    url_hash VARCHAR(64) NOT NULL,
    url TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_agent_web_resource_run
        FOREIGN KEY (agent_run_id) REFERENCES agent_run (id) ON DELETE CASCADE,
    CONSTRAINT uq_agent_web_resource_run_url UNIQUE (agent_run_id, url_hash)
);

CREATE INDEX IF NOT EXISTS idx_agent_web_resource_run
    ON agent_web_resource (agent_run_id);

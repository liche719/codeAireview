ALTER TABLE review_task
    ADD COLUMN IF NOT EXISTS execution_token VARCHAR(64),
    ADD COLUMN IF NOT EXISTS lease_until TIMESTAMP,
    ADD COLUMN IF NOT EXISTS attempt_count INT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS publish_status VARCHAR(32) NOT NULL DEFAULT 'NOT_REQUESTED',
    ADD COLUMN IF NOT EXISTS publish_attempt_count INT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS publish_error TEXT,
    ADD COLUMN IF NOT EXISTS publish_next_attempt_at TIMESTAMP;

CREATE INDEX IF NOT EXISTS idx_review_task_execution_lease
    ON review_task (status, lease_until);

CREATE INDEX IF NOT EXISTS idx_review_task_publish_status
    ON review_task (publish_status, updated_at);

CREATE TABLE IF NOT EXISTS review_task_outbox (
    id BIGSERIAL PRIMARY KEY,
    task_id BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    attempt_count INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    locked_at TIMESTAMP,
    last_error TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_review_task_outbox_task UNIQUE (task_id),
    CONSTRAINT fk_review_task_outbox_task
        FOREIGN KEY (task_id) REFERENCES review_task (id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_review_task_outbox_due
    ON review_task_outbox (status, next_attempt_at, id);

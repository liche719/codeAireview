ALTER TABLE pr_command_task
    ADD COLUMN IF NOT EXISTS execution_token VARCHAR(64),
    ADD COLUMN IF NOT EXISTS lease_until TIMESTAMP,
    ADD COLUMN IF NOT EXISTS attempt_count INT NOT NULL DEFAULT 0;

CREATE INDEX IF NOT EXISTS idx_pr_command_task_expired_execution
    ON pr_command_task (lease_until)
    WHERE status = 'RUNNING' AND lease_until IS NOT NULL;

CREATE TABLE IF NOT EXISTS pr_command_task_outbox (
    id BIGSERIAL PRIMARY KEY,
    command_task_id BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    attempt_count INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    locked_at TIMESTAMP,
    last_error TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_pr_command_task_outbox_task
        FOREIGN KEY (command_task_id) REFERENCES pr_command_task (id) ON DELETE CASCADE,
    CONSTRAINT uq_pr_command_task_outbox_task UNIQUE (command_task_id)
);

CREATE INDEX IF NOT EXISTS idx_pr_command_task_outbox_due
    ON pr_command_task_outbox (status, next_attempt_at);

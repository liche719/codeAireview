CREATE TABLE IF NOT EXISTS pr_command_task (
    id BIGSERIAL PRIMARY KEY,
    command_type VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    repo_owner VARCHAR(128) NOT NULL,
    repo_name VARCHAR(128) NOT NULL,
    pr_number INT NOT NULL,
    pr_url VARCHAR(512) NOT NULL,
    title VARCHAR(255),
    head_sha VARCHAR(128),
    comment_id BIGINT,
    comment_body TEXT,
    comment_user_login VARCHAR(128),
    dry_run BOOLEAN DEFAULT FALSE,
    generated_patch TEXT,
    commit_sha VARCHAR(128),
    error_message TEXT,
    started_at TIMESTAMP,
    finished_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_pr_command_task_repo_pr
    ON pr_command_task (repo_owner, repo_name, pr_number);

CREATE INDEX IF NOT EXISTS idx_pr_command_task_status
    ON pr_command_task (status);

CREATE INDEX IF NOT EXISTS idx_pr_command_task_comment_id
    ON pr_command_task (comment_id);

CREATE TABLE IF NOT EXISTS pr_command_task_log (
    id BIGSERIAL PRIMARY KEY,
    command_task_id BIGINT NOT NULL,
    step VARCHAR(64) NOT NULL,
    success BOOLEAN DEFAULT TRUE,
    message TEXT,
    detail TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_pr_command_task_log_task_id
    ON pr_command_task_log (command_task_id);

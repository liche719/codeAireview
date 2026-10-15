ALTER TABLE pr_command_task
    ADD COLUMN IF NOT EXISTS source_task_id BIGINT,
    ADD COLUMN IF NOT EXISTS approval_comment_id BIGINT,
    ADD COLUMN IF NOT EXISTS approved_by VARCHAR(128),
    ADD COLUMN IF NOT EXISTS comment_author_association VARCHAR(64),
    ADD COLUMN IF NOT EXISTS patch_payload TEXT,
    ADD COLUMN IF NOT EXISTS patch_sha256 VARCHAR(64),
    ADD COLUMN IF NOT EXISTS allowed_paths_json TEXT,
    ADD COLUMN IF NOT EXISTS target_branch VARCHAR(255),
    ADD COLUMN IF NOT EXISTS created_pr_number INT,
    ADD COLUMN IF NOT EXISTS created_pr_url VARCHAR(512),
    ADD COLUMN IF NOT EXISTS execution_stage VARCHAR(64);

CREATE INDEX IF NOT EXISTS idx_pr_command_task_source_task_id
    ON pr_command_task (source_task_id);

CREATE UNIQUE INDEX IF NOT EXISTS uq_pr_command_task_fix_publish_source
    ON pr_command_task (source_task_id)
    WHERE command_type = 'FIX_PUBLISH'
      AND source_task_id IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_pr_command_task_fix_merge_source
    ON pr_command_task (source_task_id)
    WHERE command_type = 'FIX_MERGE'
      AND source_task_id IS NOT NULL;

ALTER TABLE review_task
    ADD COLUMN IF NOT EXISTS head_sha VARCHAR(128);

CREATE INDEX IF NOT EXISTS idx_review_task_repo_pr_head_sha
    ON review_task (repo_owner, repo_name, pr_number, head_sha);

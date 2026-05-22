WITH duplicate_review_tasks AS (
    SELECT id,
           ROW_NUMBER() OVER (
               PARTITION BY repo_owner, repo_name, pr_number, head_sha, review_comment_mode
               ORDER BY id DESC
           ) AS row_num
    FROM review_task
    WHERE head_sha IS NOT NULL
      AND status IN ('PENDING', 'RUNNING', 'SUCCESS')
)
UPDATE review_task
SET head_sha = NULL
WHERE id IN (
    SELECT id
    FROM duplicate_review_tasks
    WHERE row_num > 1
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_review_task_active_head_mode
    ON review_task (repo_owner, repo_name, pr_number, head_sha, review_comment_mode)
    WHERE head_sha IS NOT NULL
      AND status IN ('PENDING', 'RUNNING', 'SUCCESS');

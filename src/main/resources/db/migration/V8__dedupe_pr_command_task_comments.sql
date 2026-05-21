WITH ranked_fix_commands AS (
    SELECT
        id,
        ROW_NUMBER() OVER (
            PARTITION BY command_type, repo_owner, repo_name, pr_number, comment_id
            ORDER BY id DESC
        ) AS duplicate_rank
    FROM pr_command_task
    WHERE command_type = 'FIX'
      AND comment_id IS NOT NULL
)
UPDATE pr_command_task task
SET comment_id = NULL
FROM ranked_fix_commands ranked
WHERE task.id = ranked.id
  AND ranked.duplicate_rank > 1;

CREATE UNIQUE INDEX IF NOT EXISTS uq_pr_command_task_fix_comment_id
    ON pr_command_task (command_type, repo_owner, repo_name, pr_number, comment_id)
    WHERE command_type = 'FIX'
      AND comment_id IS NOT NULL;

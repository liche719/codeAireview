ALTER TABLE pr_command_task
    ADD COLUMN IF NOT EXISTS command_text TEXT,
    ADD COLUMN IF NOT EXISTS response_body TEXT;

CREATE UNIQUE INDEX IF NOT EXISTS uq_pr_command_task_chat_comment_id
    ON pr_command_task (command_type, repo_owner, repo_name, pr_number, comment_id)
    WHERE command_type = 'CHAT'
      AND comment_id IS NOT NULL;

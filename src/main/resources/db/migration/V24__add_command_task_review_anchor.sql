-- diff 行评论里的 @mention（R14）。
--
-- 用户在 diff 的某一行上提问时，任务需要记住"问的是哪一行"，
-- 以及回复要投递回哪个线程——否则回复会变成一条新的顶层 PR 评论，
-- 与提问的上下文脱节。
--
-- reply_to_comment_id 存的是**线程根**评论 id：GitHub 的
-- POST /pulls/{n}/comments/{id}/replies 要求传线程根，
-- 传一条回复自己的 id 会失败。

ALTER TABLE pr_command_task ADD COLUMN IF NOT EXISTS anchor_path VARCHAR(512);
ALTER TABLE pr_command_task ADD COLUMN IF NOT EXISTS anchor_line INTEGER;
ALTER TABLE pr_command_task ADD COLUMN IF NOT EXISTS reply_to_comment_id BIGINT;

COMMENT ON COLUMN pr_command_task.anchor_path IS 'diff 行评论锚定的文件路径（R14）';
COMMENT ON COLUMN pr_command_task.anchor_line IS 'diff 行评论锚定的行号（R14）';
COMMENT ON COLUMN pr_command_task.reply_to_comment_id IS '回复投递到的线程根评论 id（R14）；为空表示发顶层评论';

CREATE INDEX IF NOT EXISTS idx_pr_command_task_reply_to
    ON pr_command_task (reply_to_comment_id)
    WHERE reply_to_comment_id IS NOT NULL;

-- 按仓库统计花费（2026-10-29）
--
-- LLM 调用点只有 model/token，拿不到仓库；这里补 task_id，
-- 统计时 JOIN review_task 即可按 repo_owner/repo_name 聚合。
ALTER TABLE llm_usage ADD COLUMN IF NOT EXISTS task_id BIGINT;

CREATE INDEX IF NOT EXISTS idx_llm_usage_task_id ON llm_usage (task_id);

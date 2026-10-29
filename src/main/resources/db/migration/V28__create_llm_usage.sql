-- LLM 用量持久化（2026-10-29）
--
-- 之前 token 只在 Micrometer 计数器里（累计值、无窗口、无维度），
-- 回答不了"最近 7 天花了多少、花在哪个模型上、哪个仓库最贵"。
CREATE TABLE IF NOT EXISTS llm_usage (
    id               BIGSERIAL PRIMARY KEY,
    model_name       VARCHAR(128),
    prompt_tokens    BIGINT NOT NULL DEFAULT 0,
    completion_tokens BIGINT NOT NULL DEFAULT 0,
    estimated_cost   DOUBLE PRECISION,
    created_at       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_llm_usage_created_at ON llm_usage (created_at DESC);
CREATE INDEX IF NOT EXISTS idx_llm_usage_model ON llm_usage (model_name, created_at DESC);

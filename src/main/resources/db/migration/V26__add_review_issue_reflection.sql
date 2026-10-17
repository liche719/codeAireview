-- LLM finding 的自省闸门（R18）。
--
-- 为什么要把分数落库：被自省判定为"不成立"的 finding 会被抑制（不发布），
-- 但必须可审计——否则"模型把真问题丢了"这件事永远无法复盘。
-- R7 已经确立了"抑制不删除、仍落库、摘要上报条数"的原则，这里沿用。
--
-- reflection_status 取值：
--   SCORED  —— 自省给出分数
--   FAILED  —— 自省调用失败或输出无法解析（分数取配置的 score-on-reflection-failure）
--   SKIPPED —— 未参与自省（例如确定性规则产生的 finding，或超过条数上限）

ALTER TABLE review_issue ADD COLUMN IF NOT EXISTS reflection_score INTEGER;
ALTER TABLE review_issue ADD COLUMN IF NOT EXISTS reflection_status VARCHAR(16);

COMMENT ON COLUMN review_issue.reflection_score IS '自省评分 0-10（R18）；为空表示未启用或未评分';
COMMENT ON COLUMN review_issue.reflection_status IS 'SCORED / FAILED / SKIPPED（R18）';

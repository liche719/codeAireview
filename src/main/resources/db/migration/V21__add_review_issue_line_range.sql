-- 多行区间评论（R4）。
--
-- 一个 finding 常跨多行；此前只能锚定其中一行。GitHub 支持 start_line/line 区间评论，
-- 但要求两者位于 diff 的同一侧且都在 diff 内。
--
-- 纯新增可空列：历史数据与单行评论 start_line 为 NULL。

ALTER TABLE review_issue ADD COLUMN IF NOT EXISTS start_line INT;
ALTER TABLE review_issue ADD COLUMN IF NOT EXISTS end_line INT;
-- 显式持久化落点所在侧，避免重复计算 DiffLineMapper，也让"文案与实际判定范围不符"不再可能。
ALTER TABLE review_issue ADD COLUMN IF NOT EXISTS line_side VARCHAR(8);

COMMENT ON COLUMN review_issue.start_line IS '区间评论起始行；单行评论为 NULL';
COMMENT ON COLUMN review_issue.end_line IS '区间评论结束行（即锚定行）；单行评论为 NULL';
COMMENT ON COLUMN review_issue.line_side IS '落点所在侧 RIGHT/LEFT；由 DiffLineMapper 判定后持久化';

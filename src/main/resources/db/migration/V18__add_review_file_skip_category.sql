-- 为 review_file 增加机器可读的跳过原因分类。
--
-- skip_reason 是给人读的自由文本，无法用于分组统计。摘要的「审查覆盖」区块需要
-- 按原因分组告诉用户"哪些文件没审、为什么"，因此需要这一列。
--
-- 纯新增列，历史行为 NULL（表示该行由旧版本写入，未分类）。

ALTER TABLE review_file ADD COLUMN IF NOT EXISTS skip_category VARCHAR(32);

CREATE INDEX IF NOT EXISTS idx_review_file_task_skip
    ON review_file (task_id, skipped, skip_category);

COMMENT ON COLUMN review_file.skip_category IS '文件未进入审查的原因分类，取值见 ReviewFileSkipCategory 枚举；旧数据为 NULL';

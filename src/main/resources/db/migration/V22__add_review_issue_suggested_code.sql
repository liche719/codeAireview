-- 可一键应用的修复建议（R5）。
--
-- GitHub 的 ```suggestion 代码块允许评审者一键应用。但它有一个硬前提：
-- 建议必须锚定在 diff **新侧**的连续行上，且**不能覆盖被删除的行**——
-- 否则点下去会得到错误结果。
--
-- suggested_code 存的是"替换后的完整代码块"，不是 diff。
--
-- 注意：这里**不存**"是否可一键应用"的结论。可应用性依赖发布时才会计算的
-- DiffLineMapping（需要 patch），在落库时还不知道；存一个恒为 false 的列只会误导。
-- 该判定由 ReviewSuggestionValidator 在发布时实时完成。

ALTER TABLE review_issue ADD COLUMN IF NOT EXISTS suggested_code TEXT;

COMMENT ON COLUMN review_issue.suggested_code IS '替换后的完整代码块；为空表示这条 finding 没有可应用的修复建议。是否可一键应用在发布时由 ReviewSuggestionValidator 判定';

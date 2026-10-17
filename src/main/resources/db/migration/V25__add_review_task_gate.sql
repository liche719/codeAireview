-- 构建门禁判定结果（R17）。
--
-- 为什么要把判定结果落库：门禁一旦开启，判成 failure 会**阻塞他人合并**。
-- 事后必须能回答"这次为什么判 failure、依据是什么"，
-- 否则被挡住的人无从申诉，维护者也无从判断门禁是不是在误报。
--
-- gate_status 存本项目的三态（SUCCESS/NEUTRAL/FAILURE），
-- 注意它与 GitHub commit status 的取值不是一回事：
-- commit status 只有 error/failure/pending/success，没有 neutral，
-- NEUTRAL 在写 status 时被映射为 success（见 ReviewGateEvaluator.toCommitStatus）。

ALTER TABLE review_task ADD COLUMN IF NOT EXISTS gate_status VARCHAR(16);
ALTER TABLE review_task ADD COLUMN IF NOT EXISTS gate_reason VARCHAR(255);

COMMENT ON COLUMN review_task.gate_status IS '门禁判定：SUCCESS / NEUTRAL / FAILURE（R17）；为空表示门禁未启用或未判定';
COMMENT ON COLUMN review_task.gate_reason IS '判定依据，用于审计"为什么这次判 failure"（R17）';

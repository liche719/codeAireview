-- 审计日志（2026-10-29）
--
-- 企业部署里"谁触发了审查、谁批准了修复、谁改了配置"必须可查。
-- 之前这些信息只在应用日志里，日志会轮转、也不会回答"某个 PR 被谁触发了 3 次"。
CREATE TABLE IF NOT EXISTS audit_log (
    id           BIGSERIAL PRIMARY KEY,
    action       VARCHAR(48)  NOT NULL,
    actor        VARCHAR(128),
    source_ip    VARCHAR(64),
    target       VARCHAR(256),
    result       VARCHAR(16)  NOT NULL,
    detail       VARCHAR(1000),
    created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_audit_log_created_at ON audit_log (created_at DESC);
CREATE INDEX IF NOT EXISTS idx_audit_log_action ON audit_log (action, created_at DESC);

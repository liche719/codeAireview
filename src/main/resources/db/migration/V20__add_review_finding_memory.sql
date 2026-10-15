-- 跨 headSha 的审查 finding 记忆。
--
-- 解决的问题：新 headSha = 全新任务 = 全新问题集，系统不知道上一次审查提过什么。
-- 而 synchronize 是全量重审，于是同样的 finding 会在每次 push 后被**重复评论**一遍。
--
-- 唯一索引键是 (repo_owner, repo_name, pr_number, finding_fingerprint)：
-- 指纹刻意不含行号（行号随 commit 漂移），见 ReviewFindingFingerprint。

CREATE TABLE IF NOT EXISTS review_finding_memory (
    id BIGSERIAL PRIMARY KEY,
    repo_owner VARCHAR(128) NOT NULL,
    repo_name VARCHAR(128) NOT NULL,
    pr_number INT NOT NULL,
    finding_fingerprint VARCHAR(64) NOT NULL,
    code_fingerprint VARCHAR(64),
    issue_type VARCHAR(64),
    file_path VARCHAR(512),
    severity VARCHAR(32),
    title VARCHAR(255),
    state VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    reopened_count INT NOT NULL DEFAULT 0,
    first_task_id BIGINT,
    last_task_id BIGINT,
    first_seen_head_sha VARCHAR(128),
    last_seen_head_sha VARCHAR(128),
    resolved_at_head_sha VARCHAR(128),
    suppressed_reason VARCHAR(255),
    first_seen_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_seen_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE review_finding_memory IS
    '跨 headSha 的 finding 记忆；用于避免同一问题在每次 push 后重复评论。属派生数据，丢表不丢失真相';
COMMENT ON COLUMN review_finding_memory.finding_fingerprint IS
    'sha256(filePath|normalizedTitle|issueType) 前 16 位；不含行号';
COMMENT ON COLUMN review_finding_memory.code_fingerprint IS
    'sha256(filePath|normalizedSnippet) 前 16 位；与 finding_fingerprint 为 OR 匹配';
COMMENT ON COLUMN review_finding_memory.state IS 'ACTIVE / RESOLVED / DISMISSED';

CREATE UNIQUE INDEX IF NOT EXISTS uq_finding_memory_key
    ON review_finding_memory (repo_owner, repo_name, pr_number, finding_fingerprint);

CREATE INDEX IF NOT EXISTS idx_finding_memory_state
    ON review_finding_memory (repo_owner, repo_name, pr_number, state);

-- 死信消息持久化。
--
-- 背景：两个 DLQ（codepilot.review.task.dlq / codepilot.pr.command.task.dlq）此前只有声明与绑定，
-- **没有任何消费者**。最终失败的消息进入 DLQ 后永久滞留，既无告警也无重放手段，
-- 而文档却声称"DLQ 保存最终消费失败消息，便于排查"——承诺了代码没有提供的能力。
--
-- payload_digest 与 status 的唯一索引让同一条消息反复死亡时只累加 attempt_count，
-- 不产生重复行。status 参与唯一性是为了允许"重放后再次死亡"生成新记录。

CREATE TABLE IF NOT EXISTS dead_letter_message (
    id BIGSERIAL PRIMARY KEY,
    source_queue VARCHAR(128) NOT NULL,
    message_type VARCHAR(64) NOT NULL,
    payload TEXT NOT NULL,
    payload_digest VARCHAR(64) NOT NULL,
    error_type VARCHAR(128),
    error_message TEXT,
    attempt_count INT NOT NULL DEFAULT 0,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    replayed_at TIMESTAMP,
    replayed_by VARCHAR(128),
    first_seen_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_seen_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_dead_letter_status
    ON dead_letter_message (status, last_seen_at);

CREATE UNIQUE INDEX IF NOT EXISTS uq_dead_letter_digest_status
    ON dead_letter_message (payload_digest, status);

COMMENT ON TABLE dead_letter_message IS '最终消费失败的消息；由 DeadLetterConsumer 先落库再 ack，保证不丢';
COMMENT ON COLUMN dead_letter_message.status IS 'PENDING / REPLAYED / DISCARDED';

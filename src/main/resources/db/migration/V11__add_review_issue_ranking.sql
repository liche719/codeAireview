ALTER TABLE review_issue
    ADD COLUMN IF NOT EXISTS final_score INT DEFAULT 0,
    ADD COLUMN IF NOT EXISTS publish_decision VARCHAR(32) DEFAULT 'PUBLISH',
    ADD COLUMN IF NOT EXISTS suppression_reason VARCHAR(255),
    ADD COLUMN IF NOT EXISTS comment_channel VARCHAR(32) DEFAULT 'INLINE';

CREATE INDEX IF NOT EXISTS idx_review_issue_publish_decision
    ON review_issue (publish_decision);

CREATE INDEX IF NOT EXISTS idx_review_issue_final_score
    ON review_issue (final_score);

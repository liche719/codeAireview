ALTER TABLE review_issue
    ADD COLUMN IF NOT EXISTS issue_type_zh VARCHAR(64);

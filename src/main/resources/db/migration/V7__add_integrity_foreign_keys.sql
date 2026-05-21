ALTER TABLE review_file
    ADD CONSTRAINT fk_review_file_task
    FOREIGN KEY (task_id)
    REFERENCES review_task (id)
    ON DELETE CASCADE
    NOT VALID;

ALTER TABLE review_issue
    ADD CONSTRAINT fk_review_issue_task
    FOREIGN KEY (task_id)
    REFERENCES review_task (id)
    ON DELETE CASCADE
    NOT VALID;

ALTER TABLE llm_call_log
    ADD CONSTRAINT fk_llm_call_log_task
    FOREIGN KEY (task_id)
    REFERENCES review_task (id)
    ON DELETE SET NULL
    NOT VALID;

ALTER TABLE rule_chunk
    ADD CONSTRAINT fk_rule_chunk_document
    FOREIGN KEY (document_id)
    REFERENCES rule_document (id)
    ON DELETE CASCADE
    NOT VALID;

ALTER TABLE pr_command_task_log
    ADD CONSTRAINT fk_pr_command_task_log_task
    FOREIGN KEY (command_task_id)
    REFERENCES pr_command_task (id)
    ON DELETE CASCADE
    NOT VALID;

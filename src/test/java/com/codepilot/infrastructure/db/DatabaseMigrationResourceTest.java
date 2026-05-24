package com.codepilot.infrastructure.db;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class DatabaseMigrationResourceTest {

    @Test
    void shouldAddForeignKeysWithoutRejectingExistingDirtyRowsDuringMigration() throws Exception {
        String migration = new ClassPathResource("db/migration/V7__add_integrity_foreign_keys.sql")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(migration)
                .contains("ALTER TABLE review_file")
                .contains("CONSTRAINT fk_review_file_task")
                .contains("REFERENCES review_task (id)")
                .contains("ALTER TABLE review_issue")
                .contains("CONSTRAINT fk_review_issue_task")
                .contains("ALTER TABLE llm_call_log")
                .contains("ON DELETE SET NULL")
                .contains("ALTER TABLE rule_chunk")
                .contains("REFERENCES rule_document (id)")
                .contains("ALTER TABLE pr_command_task_log")
                .contains("REFERENCES pr_command_task (id)");
        assertThat(migration.split("NOT VALID", -1).length - 1).isEqualTo(5);
        assertThat(migration.split("ON DELETE CASCADE", -1).length - 1).isEqualTo(4);
    }

    @Test
    void shouldEnforcePrCommandCommentIdempotencyAfterCleaningLegacyDuplicates() throws Exception {
        String migration = new ClassPathResource("db/migration/V8__dedupe_pr_command_task_comments.sql")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(migration)
                .contains("ROW_NUMBER() OVER")
                .contains("PARTITION BY command_type, repo_owner, repo_name, pr_number, comment_id")
                .contains("SET comment_id = NULL")
                .contains("CREATE UNIQUE INDEX IF NOT EXISTS uq_pr_command_task_fix_comment_id")
                .contains("WHERE command_type = 'FIX'")
                .contains("AND comment_id IS NOT NULL");
    }

    @Test
    void shouldEnforceReviewTaskHeadShaIdempotencyAfterCleaningLegacyDuplicates() throws Exception {
        String migration = new ClassPathResource("db/migration/V9__dedupe_review_task_head_sha.sql")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(migration)
                .contains("ROW_NUMBER() OVER")
                .contains("PARTITION BY repo_owner, repo_name, pr_number, head_sha, review_comment_mode")
                .contains("SET head_sha = NULL")
                .contains("CREATE UNIQUE INDEX IF NOT EXISTS uq_review_task_active_head_mode")
                .contains("WHERE head_sha IS NOT NULL")
                .contains("status IN ('PENDING', 'RUNNING', 'SUCCESS')");
    }

    @Test
    void shouldCreateAiReviewCacheTableForReusableLlmReviewResults() throws Exception {
        String migration = new ClassPathResource("db/migration/V10__add_ai_review_cache.sql")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(migration)
                .contains("CREATE TABLE IF NOT EXISTS ai_review_cache")
                .contains("cache_key VARCHAR(128) NOT NULL")
                .contains("result_json TEXT NOT NULL")
                .contains("CREATE UNIQUE INDEX IF NOT EXISTS uq_ai_review_cache_key")
                .contains("CREATE INDEX IF NOT EXISTS idx_ai_review_cache_last_used_at");
    }
}

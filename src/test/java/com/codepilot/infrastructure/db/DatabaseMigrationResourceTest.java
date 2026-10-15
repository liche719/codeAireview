package com.codepilot.infrastructure.db;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class DatabaseMigrationResourceTest {

    @Test
    void shouldCreateRuleChunkVectorIndexWithKnownEmbeddingDimension() throws Exception {
        String migration = new ClassPathResource("db/migration/V1__init.sql")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(migration)
                .contains("embedding VECTOR(${embeddingDimension})")
                .contains("USING hnsw (embedding vector_cosine_ops)")
                .doesNotContain("embedding VECTOR,");
    }

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

    @Test
    void shouldPersistAgentChatInputOutputAndEnforceCommentIdempotency() throws Exception {
        String migration = new ClassPathResource("db/migration/V13__add_agent_chat_task_fields.sql")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(migration)
                .contains("ADD COLUMN IF NOT EXISTS command_text TEXT")
                .contains("ADD COLUMN IF NOT EXISTS response_body TEXT")
                .contains("uq_pr_command_task_chat_comment_id")
                .contains("WHERE command_type = 'CHAT'");
    }

    @Test
    void shouldPersistApprovalArtifactsAndEnforceActionIdempotency() throws Exception {
        String migration = new ClassPathResource("db/migration/V14__add_fix_approval_workflow.sql")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(migration)
                .contains("ADD COLUMN IF NOT EXISTS source_task_id BIGINT")
                .contains("ADD COLUMN IF NOT EXISTS patch_payload TEXT")
                .contains("ADD COLUMN IF NOT EXISTS patch_sha256 VARCHAR(64)")
                .contains("ADD COLUMN IF NOT EXISTS target_branch VARCHAR(255)")
                .contains("uq_pr_command_task_fix_publish_source")
                .contains("WHERE command_type = 'FIX_PUBLISH'")
                .contains("uq_pr_command_task_fix_merge_source")
                .contains("WHERE command_type = 'FIX_MERGE'");
    }

    @Test
    void shouldAddReliableCommandAndResumableAgentRuntimeTables() throws Exception {
        String reliableCommand = new ClassPathResource("db/migration/V15__harden_pr_command_execution.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        String agentRuntime = new ClassPathResource("db/migration/V16__add_resumable_agent_runtime.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        String webResources = new ClassPathResource("db/migration/V17__add_agent_web_resource_allowlist.sql")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(reliableCommand)
                .contains("execution_token VARCHAR(64)")
                .contains("CREATE TABLE IF NOT EXISTS pr_command_task_outbox")
                .contains("UNIQUE (command_task_id)");
        assertThat(agentRuntime)
                .contains("CREATE TABLE IF NOT EXISTS agent_run")
                .contains("CREATE TABLE IF NOT EXISTS agent_step")
                .contains("WAITING_WORKFLOW")
                .contains("UNIQUE (command_task_id)");
        assertThat(webResources)
                .contains("CREATE TABLE IF NOT EXISTS agent_web_resource")
                .contains("UNIQUE (agent_run_id, url_hash)");
    }
}

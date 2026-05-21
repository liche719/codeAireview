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
}

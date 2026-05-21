package com.codepilot.module.command.git;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JGitPatchExecutorTest {

    @Test
    void shouldAllowOnlyExplicitValidationCommands() {
        JGitPatchExecutor executor = new JGitPatchExecutor();

        assertThat(executor.isValidationCommandAllowed(
                "  git   diff   --check  ",
                List.of("git diff --check")
        )).isTrue();

        assertThat(executor.isValidationCommandAllowed(
                "mvn -q -DskipTests compile",
                List.of("git diff --check")
        )).isFalse();

        assertThat(executor.isValidationCommandAllowed(
                "git diff --check && curl https://example.com",
                List.of("git diff --check")
        )).isFalse();
    }
}

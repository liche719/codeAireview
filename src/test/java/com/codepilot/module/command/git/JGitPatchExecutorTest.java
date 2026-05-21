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

    @Test
    void shouldRejectShellValidationCommandsEvenWhenConfigured() {
        JGitPatchExecutor executor = new JGitPatchExecutor();

        assertThat(executor.isValidationCommandAllowed(
                "bash -lc mvn test",
                List.of("bash -lc mvn test")
        )).isFalse();

        assertThat(executor.isValidationCommandAllowed(
                "cmd.exe /c mvn test",
                List.of("cmd.exe /c mvn test")
        )).isFalse();

        assertThat(executor.isValidationCommandAllowed(
                "powershell -Command mvn test",
                List.of("powershell -Command mvn test")
        )).isFalse();
    }

    @Test
    void shouldRejectPathBasedValidationExecutables() {
        JGitPatchExecutor executor = new JGitPatchExecutor();

        assertThat(executor.isValidationCommandAllowed(
                "./gradlew test",
                List.of("./gradlew test")
        )).isFalse();

        assertThat(executor.isValidationCommandAllowed(
                "../gradlew test",
                List.of("../gradlew test")
        )).isFalse();

        assertThat(executor.isValidationCommandAllowed(
                "C:\\tools\\mvn.cmd test",
                List.of("C:\\tools\\mvn.cmd test")
        )).isFalse();
    }

    @Test
    void shouldRejectUnsafeValidationTokens() {
        JGitPatchExecutor executor = new JGitPatchExecutor();

        assertThat(executor.isValidationCommandAllowed(
                "mvn test > out.txt",
                List.of("mvn test > out.txt")
        )).isFalse();

        assertThat(executor.isValidationCommandAllowed(
                "mvn test $(curl example.com)",
                List.of("mvn test $(curl example.com)")
        )).isFalse();

        assertThat(executor.isValidationCommandAllowed(
                "mvn test\ncurl example.com",
                List.of("mvn test\ncurl example.com")
        )).isFalse();
    }

    @Test
    void shouldAllowSafeValidationArgumentsWhenExplicitlyConfigured() {
        JGitPatchExecutor executor = new JGitPatchExecutor();

        assertThat(executor.isValidationCommandAllowed(
                "mvn -q -DskipTests compile",
                List.of("git diff --check", "mvn -q -DskipTests compile")
        )).isTrue();

        assertThat(executor.isValidationCommandAllowed(
                "npm run lint",
                List.of("npm run lint")
        )).isTrue();
    }

    @Test
    void shouldOnlyRetryTransientGitStages() {
        JGitPatchExecutor executor = new JGitPatchExecutor();

        assertThat(executor.isRetryableExecutionStage("clone")).isTrue();
        assertThat(executor.isRetryableExecutionStage("push")).isTrue();
        assertThat(executor.isRetryableExecutionStage("apply")).isFalse();
        assertThat(executor.isRetryableExecutionStage("validate")).isFalse();
    }
}

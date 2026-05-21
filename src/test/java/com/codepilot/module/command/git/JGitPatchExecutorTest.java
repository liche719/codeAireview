package com.codepilot.module.command.git;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    @Test
    void shouldRedactSecretsFromValidationOutput() throws Exception {
        JGitPatchExecutor executor = new JGitPatchExecutor();
        Path outputFile = Files.createTempFile("codepilot-validation-test-", ".log");
        try {
            Files.writeString(outputFile, "token=ghp_123456789012345678901234567890123456");

            String output = executor.sanitizedValidationOutput(outputFile);

            assertThat(output).contains("[REDACTED]");
            assertThat(output).doesNotContain("ghp_123456789012345678901234567890123456");
        } finally {
            Files.deleteIfExists(outputFile);
        }
    }

    @Test
    void shouldRedactSecretsBeforeTruncatingValidationOutput() throws Exception {
        JGitPatchExecutor executor = new JGitPatchExecutor();
        Path outputFile = Files.createTempFile("codepilot-validation-test-", ".log");
        try {
            Files.writeString(
                    outputFile,
                    "x".repeat(11990) + " token=ghp_123456789012345678901234567890123456"
            );

            String output = executor.sanitizedValidationOutput(outputFile);

            assertThat(output)
                    .contains("[REDACTED]")
                    .doesNotContain("ghp_")
                    .doesNotContain("123456789012345678901234567890123456");
        } finally {
            Files.deleteIfExists(outputFile);
        }
    }

    @Test
    void shouldAvoidLoggingCredentialsFromRemoteUrl() {
        JGitPatchExecutor executor = new JGitPatchExecutor();

        String label = executor.safeRemoteLabel("https://user:token123456@example.com/owner/repo.git");

        assertThat(label).isEqualTo("example.com");
    }

    @Test
    void shouldIsolateValidationEnvironmentWhenInheritanceIsDisabled() throws Exception {
        JGitPatchExecutor executor = new JGitPatchExecutor();
        Path workDir = Files.createTempDirectory("codepilot-validation-env-test-");
        try {
            Map<String, String> environment = new HashMap<>();
            environment.put("PATH", "/usr/bin");
            environment.put("SystemRoot", "C:\\Windows");
            environment.put("CODEPILOT_GITHUB_TOKEN", "ghp_123456789012345678901234567890123456");
            environment.put("OPENAI_API_KEY", "sk-proj-12345678901234567890");
            environment.put("TEMP", "C:\\Users\\runner\\AppData\\Local\\Temp");
            environment.put("TMP", "C:\\Users\\runner\\AppData\\Local\\Temp");

            executor.sanitizeValidationEnvironment(environment, workDir);

            assertThat(environment)
                    .containsEntry("PATH", "/usr/bin")
                    .containsEntry("SystemRoot", "C:\\Windows")
                    .doesNotContainKeys("CODEPILOT_GITHUB_TOKEN", "OPENAI_API_KEY");
            assertThat(Path.of(environment.get("HOME"))).startsWith(workDir);
            assertThat(Path.of(environment.get("USERPROFILE"))).startsWith(workDir);
            assertThat(Path.of(environment.get("TEMP"))).startsWith(workDir);
            assertThat(Path.of(environment.get("TMP"))).startsWith(workDir);
            assertThat(Path.of(environment.get("TMPDIR"))).startsWith(workDir);
            assertThat(Files.isDirectory(Path.of(environment.get("HOME")))).isTrue();
            assertThat(Files.isDirectory(Path.of(environment.get("TEMP")))).isTrue();
        } finally {
            try (var stream = Files.walk(workDir)) {
                stream.sorted(java.util.Comparator.reverseOrder())
                        .forEach(path -> {
                            try {
                                Files.deleteIfExists(path);
                            } catch (Exception ignored) {
                                // Best effort cleanup for test temp directories.
                            }
                        });
            }
        }
    }
}

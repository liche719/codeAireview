package com.codepilot.module.command.git;

import com.codepilot.module.command.config.GithubCommandProperties;
import com.codepilot.module.command.fix.FixPatchScopeValidator;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class JGitPatchExecutorTest {

    private final JGitPatchExecutor executor = new JGitPatchExecutor(
            new FixPatchScopeValidator(new GithubCommandProperties())
    );

    @Test
    void shouldAllowOnlyExplicitValidationCommands() {
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
    void shouldClassifyBuildValidationCommandsAsUnsafeWithoutSandboxOptIn() {
        assertThat(executor.isBuildValidationCommand("git diff --check")).isFalse();
        assertThat(executor.isBuildValidationCommand("mvn -q -DskipTests compile")).isTrue();
        assertThat(executor.isBuildValidationCommand("npm run lint")).isTrue();
        assertThat(executor.isBuildValidationCommand("gradle test")).isTrue();
    }

    @Test
    void shouldRejectBuildValidationCommandsByDefaultBeforeClone() {
        GitPatchExecutionRequest request = validRequest();
        request.setValidationCommand("mvn -q -DskipTests compile");
        request.setAllowedValidationCommands(List.of("mvn -q -DskipTests compile"));
        request.setAllowBuildValidationCommands(false);

        GitPatchExecutionResult result = executor.execute(request);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.isRetryable()).isFalse();
        assertThat(result.getMessage()).contains("execute PR code");
        assertThat(result.getDetail()).contains("stage=validate-request");
    }

    @Test
    void shouldOnlyRetryTransientGitStages() {
        assertThat(executor.isRetryableExecutionStage("clone")).isTrue();
        assertThat(executor.isRetryableExecutionStage("push")).isTrue();
        assertThat(executor.isRetryableExecutionStage("apply")).isFalse();
        assertThat(executor.isRetryableExecutionStage("validate")).isFalse();
    }

    @Test
    void shouldRejectPatchExecutionWithoutAllowedPaths() {
        GitPatchExecutionRequest request = validRequest();
        request.setAllowedPaths(Set.of());

        GitPatchExecutionResult result = executor.execute(request);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.isRetryable()).isFalse();
        assertThat(result.getMessage()).contains("allowedPaths are required");
    }

    @Test
    void shouldRejectPatchExecutionOutsideAllowedPathsBeforeClone() {
        GitPatchExecutionRequest request = validRequest();
        request.setAllowedPaths(Set.of("src/main/java/Demo.java"));
        request.setPatch("""
                diff --git a/src/main/java/Other.java b/src/main/java/Other.java
                --- a/src/main/java/Other.java
                +++ b/src/main/java/Other.java
                @@ -1 +1 @@
                -old
                +new
                """);

        GitPatchExecutionResult result = executor.execute(request);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.isRetryable()).isFalse();
        assertThat(result.getMessage()).contains("Automatic fixes can only modify selected issue files");
    }

    @Test
    void shouldRedactSecretsFromValidationOutput() throws Exception {
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
        String label = executor.safeRemoteLabel("https://user:token123456@example.com/owner/repo.git");

        assertThat(label).isEqualTo("example.com");
    }

    @Test
    void shouldIsolateValidationEnvironmentWhenInheritanceIsDisabled() throws Exception {
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

    private GitPatchExecutionRequest validRequest() {
        GitPatchExecutionRequest request = new GitPatchExecutionRequest();
        request.setCloneUrl("https://github.com/liche719/codeAireview.git");
        request.setBranch("feature/fix");
        request.setPatch("""
                diff --git a/src/main/java/Demo.java b/src/main/java/Demo.java
                --- a/src/main/java/Demo.java
                +++ b/src/main/java/Demo.java
                @@ -1 +1 @@
                -old
                +new
                """);
        request.setAllowedPaths(Set.of("src/main/java/Demo.java"));
        request.setToken("github-token");
        request.setCommitMessage("fix: demo");
        request.setValidationCommand("git diff --check");
        request.setAllowedValidationCommands(List.of("git diff --check"));
        request.setDryRun(true);
        return request;
    }
}

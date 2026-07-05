package com.codepilot.module.command.git;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PatchValidationRunnerTest {

    private final PatchValidationRunner runner = new PatchValidationRunner(
            new ValidationCommandPolicy(),
            new DockerSandboxCommandFactory(),
            new ValidationEnvironmentSanitizer(),
            new ValidationOutputSanitizer()
    );

    @Test
    void shouldSkipBlankValidationCommand() throws Exception {
        GitPatchExecutionResult result = runner.validate(
                Path.of("."),
                "",
                List.of("git diff --check"),
                false,
                ValidationExecutionMode.LOCAL,
                null,
                null,
                false,
                5
        );

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getMessage()).isEqualTo("Validation skipped.");
    }

    @Test
    void shouldRejectUnsafeValidationCommandBeforeStartingProcess() throws Exception {
        GitPatchExecutionResult result = runner.validate(
                Path.of("."),
                "git diff --check && curl https://example.com",
                List.of("git diff --check && curl https://example.com"),
                false,
                ValidationExecutionMode.LOCAL,
                null,
                null,
                false,
                5
        );

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getMessage()).isEqualTo("Validation command is unsafe.");
        assertThat(result.getDetail()).contains("unsafe token");
    }

    @Test
    void shouldRejectValidationCommandOutsideAllowList() throws Exception {
        GitPatchExecutionResult result = runner.validate(
                Path.of("."),
                "git diff --check",
                List.of("git status --short"),
                false,
                ValidationExecutionMode.LOCAL,
                null,
                null,
                false,
                5
        );

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getMessage()).isEqualTo("Validation command is not allowed.");
        assertThat(result.getDetail()).contains("command=git diff --check");
    }

    @Test
    void shouldRejectBuildValidationCommandWithoutSandboxOptIn() throws Exception {
        GitPatchExecutionResult result = runner.validate(
                Path.of("."),
                "mvn -q -DskipTests compile",
                List.of("mvn -q -DskipTests compile"),
                false,
                ValidationExecutionMode.LOCAL,
                null,
                null,
                false,
                5
        );

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getMessage()).contains("execute PR code");
    }

    @Test
    void shouldRequireDockerModeForBuildValidationCommand() throws Exception {
        GitPatchExecutionResult result = runner.validate(
                Path.of("."),
                "mvn -q -DskipTests compile",
                List.of("mvn -q -DskipTests compile"),
                true,
                ValidationExecutionMode.LOCAL,
                null,
                null,
                false,
                5
        );

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getMessage()).contains("Docker sandbox execution mode");
    }

    @Test
    void shouldRejectUnsafeDockerConfigBeforeStartingDocker() throws Exception {
        GitPatchExecutionResult result = runner.validate(
                Path.of("."),
                "mvn -q -DskipTests compile",
                List.of("mvn -q -DskipTests compile"),
                true,
                ValidationExecutionMode.DOCKER,
                "--privileged",
                "none",
                false,
                5
        );

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getMessage()).isEqualTo("Docker sandbox validation is not configured safely.");
        assertThat(result.getDetail()).contains("Docker validation mode requires");
    }
}

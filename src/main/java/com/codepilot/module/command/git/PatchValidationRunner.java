package com.codepilot.module.command.git;

import com.codepilot.common.util.SensitiveDataSanitizer;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

class PatchValidationRunner {

    private static final int DOCKER_CLEANUP_TIMEOUT_SECONDS = 10;

    private final ValidationCommandPolicy validationCommandPolicy;

    private final DockerSandboxCommandFactory dockerSandboxCommandFactory;

    private final ValidationEnvironmentSanitizer validationEnvironmentSanitizer;

    private final ValidationOutputSanitizer validationOutputSanitizer;

    PatchValidationRunner(
            ValidationCommandPolicy validationCommandPolicy,
            DockerSandboxCommandFactory dockerSandboxCommandFactory,
            ValidationEnvironmentSanitizer validationEnvironmentSanitizer,
            ValidationOutputSanitizer validationOutputSanitizer
    ) {
        this.validationCommandPolicy = validationCommandPolicy;
        this.dockerSandboxCommandFactory = dockerSandboxCommandFactory;
        this.validationEnvironmentSanitizer = validationEnvironmentSanitizer;
        this.validationOutputSanitizer = validationOutputSanitizer;
    }

    GitPatchExecutionResult validate(
            Path workDir,
            String validationCommand,
            List<String> allowedValidationCommands,
            boolean allowBuildValidationCommands,
            ValidationExecutionMode validationExecutionMode,
            String validationDockerImage,
            String validationDockerNetwork,
            boolean inheritValidationEnvironment,
            int timeoutSeconds
    ) throws IOException, InterruptedException {
        if (!StringUtils.hasText(validationCommand)) {
            return GitPatchExecutionResult.success(null, "Validation skipped.", null);
        }
        ValidationCommand parsedCommand;
        try {
            parsedCommand = validationCommandPolicy.parse(validationCommand);
        } catch (IllegalArgumentException exception) {
            return GitPatchExecutionResult.failure(
                    "Validation command is unsafe.",
                    SensitiveDataSanitizer.redact(exception.getMessage())
            );
        }
        if (!validationCommandPolicy.isValidationCommandAllowed(parsedCommand, allowedValidationCommands)) {
            return GitPatchExecutionResult.failure(
                    "Validation command is not allowed.",
                    SensitiveDataSanitizer.redact("command=" + parsedCommand.normalized()
                            + ", allowedCommands=" + allowedValidationCommands
                    )
            );
        }
        boolean buildValidationCommand = validationCommandPolicy.isBuildValidationCommand(parsedCommand);
        ValidationExecutionMode executionMode = resolveValidationExecutionMode(validationExecutionMode);
        if (!allowBuildValidationCommands && buildValidationCommand) {
            return GitPatchExecutionResult.failure(
                    "Validation command may execute PR code and is disabled by default.",
                    "command=" + parsedCommand.normalized()
                            + ", set codepilot.github.fix-validation-allow-build-commands=true only inside an isolated sandbox"
            );
        }
        if (buildValidationCommand && executionMode != ValidationExecutionMode.DOCKER) {
            return GitPatchExecutionResult.failure(
                    "Build validation commands require Docker sandbox execution mode.",
                    "command=" + parsedCommand.normalized()
                            + ", executionMode=" + executionMode
                            + ", set codepilot.github.fix-validation-execution-mode=docker"
            );
        }
        if (executionMode == ValidationExecutionMode.DOCKER) {
            return validateInDockerSandbox(
                    workDir,
                    parsedCommand,
                    validationDockerImage,
                    validationDockerNetwork,
                    timeoutSeconds
            );
        }
        return validateLocally(workDir, parsedCommand, inheritValidationEnvironment, timeoutSeconds);
    }

    private GitPatchExecutionResult validateLocally(
            Path workDir,
            ValidationCommand parsedCommand,
            boolean inheritValidationEnvironment,
            int timeoutSeconds
    ) throws IOException, InterruptedException {
        Path outputFile = Files.createTempFile("codepilot-validation-", ".log");
        try {
            ProcessBuilder processBuilder = new ProcessBuilder(parsedCommand.tokens());
            processBuilder.directory(workDir.toFile());
            if (!inheritValidationEnvironment) {
                validationEnvironmentSanitizer.sanitizeValidationEnvironment(processBuilder.environment(), workDir);
            }
            processBuilder.redirectErrorStream(true);
            processBuilder.redirectOutput(outputFile.toFile());
            Process process = processBuilder.start();
            boolean completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
                return GitPatchExecutionResult.failure(
                        "Validation command timed out after " + timeoutSeconds + " seconds.",
                        validationOutputSanitizer.sanitizedOutput(outputFile)
                );
            }
            String output = validationOutputSanitizer.sanitizedOutput(outputFile);
            if (process.exitValue() != 0) {
                return GitPatchExecutionResult.failure("Validation command failed with exit code " + process.exitValue(), output);
            }
            return GitPatchExecutionResult.success(null, "Validation command passed.", output);
        } finally {
            deleteFileQuietly(outputFile);
        }
    }

    private GitPatchExecutionResult validateInDockerSandbox(
            Path workDir,
            ValidationCommand parsedCommand,
            String validationDockerImage,
            String validationDockerNetwork,
            int timeoutSeconds
    ) throws IOException, InterruptedException {
        try {
            dockerSandboxCommandFactory.validateConfig(validationDockerImage, validationDockerNetwork);
        } catch (IllegalArgumentException exception) {
            return GitPatchExecutionResult.failure(
                    "Docker sandbox validation is not configured safely.",
                    SensitiveDataSanitizer.redact(exception.getMessage())
            );
        }

        String containerName = dockerSandboxCommandFactory.validationContainerName();
        String dockerImage = dockerSandboxCommandFactory.normalizeDockerImage(validationDockerImage);
        String dockerNetwork = dockerSandboxCommandFactory.normalizeDockerNetwork(validationDockerNetwork);
        Path outputFile = Files.createTempFile("codepilot-validation-docker-", ".log");
        try {
            DockerCommandResult createResult = runDockerCommand(
                    dockerSandboxCommandFactory.buildCreateCommand(containerName, dockerImage, dockerNetwork, parsedCommand.tokens()),
                    outputFile,
                    Math.min(timeoutSeconds, 60)
            );
            if (!createResult.success()) {
                return dockerValidationFailure("Docker sandbox container creation failed.", createResult, outputFile);
            }

            DockerCommandResult copyResult = runDockerCommand(
                    dockerSandboxCommandFactory.buildCopyCommand(workDir, containerName),
                    outputFile,
                    timeoutSeconds
            );
            if (!copyResult.success()) {
                return dockerValidationFailure("Docker sandbox workspace copy failed.", copyResult, outputFile);
            }

            DockerCommandResult startResult = runDockerCommand(
                    dockerSandboxCommandFactory.buildStartCommand(containerName),
                    outputFile,
                    timeoutSeconds
            );
            String output = validationOutputSanitizer.sanitizedOutput(outputFile);
            if (startResult.timedOut()) {
                return GitPatchExecutionResult.failure(
                        "Docker sandbox validation timed out after " + timeoutSeconds + " seconds.",
                        output
                );
            }
            if (startResult.exitCode() != 0) {
                return GitPatchExecutionResult.failure(
                        "Docker sandbox validation failed with exit code " + startResult.exitCode(),
                        output
                );
            }
            return GitPatchExecutionResult.success(null, "Docker sandbox validation command passed.", output);
        } finally {
            cleanupDockerContainer(containerName);
            deleteFileQuietly(outputFile);
        }
    }

    private GitPatchExecutionResult dockerValidationFailure(
            String message,
            DockerCommandResult result,
            Path outputFile
    ) throws IOException {
        String detail = validationOutputSanitizer.sanitizedOutput(outputFile);
        if (result.timedOut()) {
            return GitPatchExecutionResult.failure(message + " Timed out.", detail);
        }
        return GitPatchExecutionResult.failure(message + " Exit code " + result.exitCode() + ".", detail);
    }

    private DockerCommandResult runDockerCommand(
            List<String> command,
            Path outputFile,
            int timeoutSeconds
    ) throws IOException, InterruptedException {
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        validationEnvironmentSanitizer.sanitizeDockerClientEnvironment(processBuilder.environment());
        processBuilder.redirectErrorStream(true);
        processBuilder.redirectOutput(ProcessBuilder.Redirect.appendTo(outputFile.toFile()));
        Process process = processBuilder.start();
        boolean completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!completed) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
            return new DockerCommandResult(-1, true);
        }
        return new DockerCommandResult(process.exitValue(), false);
    }

    private void cleanupDockerContainer(String containerName) {
        try {
            ProcessBuilder processBuilder = new ProcessBuilder(dockerSandboxCommandFactory.buildRemoveCommand(containerName));
            validationEnvironmentSanitizer.sanitizeDockerClientEnvironment(processBuilder.environment());
            processBuilder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            processBuilder.redirectError(ProcessBuilder.Redirect.DISCARD);
            Process process = processBuilder.start();
            boolean completed = process.waitFor(DOCKER_CLEANUP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        } catch (Exception ignored) {
            // Best effort cleanup for sandbox containers.
        }
    }

    private ValidationExecutionMode resolveValidationExecutionMode(ValidationExecutionMode validationExecutionMode) {
        return validationExecutionMode == null ? ValidationExecutionMode.LOCAL : validationExecutionMode;
    }

    private void deleteFileQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Best effort cleanup for validation output files.
        }
    }

    private record DockerCommandResult(int exitCode, boolean timedOut) {

        private boolean success() {
            return !timedOut && exitCode == 0;
        }
    }
}

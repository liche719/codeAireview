package com.codepilot.module.command.git;

import lombok.extern.slf4j.Slf4j;
import com.codepilot.common.util.SensitiveDataSanitizer;
import com.codepilot.module.command.fix.FixPatchScopeValidator;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

@Slf4j
@Component
public class JGitPatchExecutor implements GitPatchExecutor {

    private static final int DEFAULT_VALIDATION_TIMEOUT_SECONDS = 300;

    private static final int MAX_VALIDATION_OUTPUT_CHARS = 12000;

    private static final String DOCKER_EXECUTABLE = "docker";

    private static final String DOCKER_CONTAINER_WORKDIR = "/workspace";

    private static final String DEFAULT_DOCKER_NETWORK = "none";

    private static final int DOCKER_CLEANUP_TIMEOUT_SECONDS = 10;

    private static final Pattern SAFE_VALIDATION_TOKEN_PATTERN = Pattern.compile("[A-Za-z0-9._:/=+%,~-]+");

    private static final Pattern SAFE_DOCKER_IMAGE_PATTERN = Pattern.compile("[A-Za-z0-9._:/@-]+");

    private static final Pattern SAFE_DOCKER_NETWORK_PATTERN = Pattern.compile("[A-Za-z0-9._-]+");

    private static final Set<String> BLOCKED_VALIDATION_EXECUTABLES = Set.of(
            "sh",
            "bash",
            "cmd",
            "cmd.exe",
            "powershell",
            "powershell.exe",
            "pwsh",
            "pwsh.exe"
    );

    private static final Set<String> BUILD_VALIDATION_EXECUTABLES = Set.of(
            "mvn",
            "mvnw",
            "mvn.cmd",
            "gradle",
            "gradlew",
            "gradle.bat",
            "npm",
            "npm.cmd",
            "yarn",
            "yarn.cmd",
            "pnpm",
            "pnpm.cmd",
            "node",
            "node.exe",
            "java",
            "java.exe",
            "python",
            "python.exe",
            "pytest",
            "pytest.exe",
            "go",
            "go.exe",
            "cargo",
            "cargo.exe"
    );

    private final FixPatchScopeValidator fixPatchScopeValidator;

    public JGitPatchExecutor(FixPatchScopeValidator fixPatchScopeValidator) {
        this.fixPatchScopeValidator = fixPatchScopeValidator;
    }

    @Override
    public GitPatchExecutionResult execute(GitPatchExecutionRequest request) {
        Path workDir = null;
        String stage = "validate-request";
        try {
            validateRequest(request);
            enforcePatchScope(request);
            String branch = request.getBranch();
            String cloneUrl = request.getCloneUrl();
            boolean dryRun = request.isDryRun();

            stage = "create-temp-dir";
            workDir = Files.createTempDirectory("codepilot-fix-");
            log.info("Git patch execution started, stage={}, branch={}, dryRun={}, hasValidationCommand={}, cloneUrlHost={}",
                    stage, branch, dryRun, StringUtils.hasText(request.getValidationCommand()), safeRemoteLabel(cloneUrl));

            UsernamePasswordCredentialsProvider credentials = new UsernamePasswordCredentialsProvider(
                    "x-access-token",
                    request.getToken()
            );

            stage = "clone";
            log.info("Git patch execution stage start, stage={}, branch={}, cloneUrlHost={}", stage, branch, safeRemoteLabel(cloneUrl));
            try (Git git = Git.cloneRepository()
                    .setURI(request.getCloneUrl())
                    .setDirectory(workDir.toFile())
                    .setBranch(request.getBranch())
                    .setCredentialsProvider(credentials)
                    .call()) {
                log.info("Git patch execution stage success, stage={}, branch={}, workDir={}", stage, branch, workDir);

                stage = "apply";
                log.info("Git patch execution stage start, stage={}, branch={}", stage, branch);
                boolean applyPatchFormat = ApplyPatchFormatApplier.isApplyPatchFormat(request.getPatch());
                if (applyPatchFormat) {
                    ApplyPatchFormatApplier.ApplyPatchApplicationResult applyResult =
                            ApplyPatchFormatApplier.apply(workDir, request.getPatch());
                    log.info("Git patch execution stage success, stage={}, branch={}, changedFiles={}, changedPaths={}",
                            stage, branch, applyResult.changedFiles(), applyResult.changedPaths());
                    if (applyResult.changedFiles() == 0) {
                        log.warn("Git patch execution stage failed, stage={}, branch={}, reason=patch applied but no file changed", stage, branch);
                        return GitPatchExecutionResult.failure("Patch applied but no file changed.", "apply_patch format produced no file changes");
                    }
                } else {
                    applyPatch(git, request.getPatch());
                    log.info("Git patch execution stage success, stage={}, branch={}", stage, branch);
                }

                stage = "validate";
                int validationTimeoutSeconds = resolveValidationTimeoutSeconds(request);
                ValidationExecutionMode validationExecutionMode = resolveValidationExecutionMode(request.getValidationExecutionMode());
                log.info("Git patch execution stage start, stage={}, branch={}, validationCommand={}, executionMode={}, timeoutSeconds={}",
                        stage,
                        branch,
                        SensitiveDataSanitizer.redact(request.getValidationCommand()),
                        validationExecutionMode,
                        validationTimeoutSeconds);
                GitPatchExecutionResult validationResult = validate(
                        workDir,
                        request.getValidationCommand(),
                        request.getAllowedValidationCommands(),
                        request.isAllowBuildValidationCommands(),
                        validationExecutionMode,
                        request.getValidationDockerImage(),
                        request.getValidationDockerNetwork(),
                        request.isInheritValidationEnvironment(),
                        validationTimeoutSeconds
                );
                if (!validationResult.isSuccess()) {
                    log.warn("Git patch execution stage failed, stage={}, branch={}, message={}",
                            stage, branch, validationResult.getMessage());
                    return validationResult;
                }
                log.info("Git patch execution stage success, stage={}, branch={}", stage, branch);

                if (request.isDryRun()) {
                    log.info("Git patch execution finished in dry-run mode, stage={}, branch={}", stage, branch);
                    return GitPatchExecutionResult.success(null, "Dry-run patch validation passed.", validationResult.getDetail());
                }

                stage = "add";
                log.info("Git patch execution stage start, stage={}, branch={}", stage, branch);
                git.add().addFilepattern(".").call();
                log.info("Git patch execution stage success, stage={}, branch={}", stage, branch);

                stage = "status";
                if (git.status().call().isClean()) {
                    log.warn("Git patch execution stage failed, stage={}, branch={}, reason=patch applied but no file changed", stage, branch);
                    return GitPatchExecutionResult.failure("Patch applied but no file changed.", null);
                }
                log.info("Git patch execution stage success, stage={}, branch={}", stage, branch);

                stage = "commit";
                log.info("Git patch execution stage start, stage={}, branch={}", stage, branch);
                String commitSha = git.commit()
                        .setMessage(request.getCommitMessage())
                        .call()
                        .getName();
                log.info("Git patch execution stage success, stage={}, branch={}, commitSha={}", stage, branch, commitSha);

                stage = "push";
                log.info("Git patch execution stage start, stage={}, branch={}, commitSha={}", stage, branch, commitSha);
                git.push()
                        .setCredentialsProvider(credentials)
                        .setRefSpecs(new RefSpec("HEAD:refs/heads/" + request.getBranch()))
                        .call();
                log.info("Git patch execution stage success, stage={}, branch={}, commitSha={}", stage, branch, commitSha);
                return GitPatchExecutionResult.success(commitSha, "Patch committed and pushed.", validationResult.getDetail());
            }
        } catch (Exception exception) {
            String branch = request == null ? null : request.getBranch();
            String cloneUrl = request == null ? null : request.getCloneUrl();
            log.warn("Git patch execution failed, stage={}, branch={}, cloneUrlHost={}, dryRun={}, errorType={}, message={}",
                    stage,
                    branch,
                    safeRemoteLabel(cloneUrl),
                    request != null && request.isDryRun(),
                    exception.getClass().getSimpleName(),
                    SensitiveDataSanitizer.redact(exception.getMessage()));
            String message = SensitiveDataSanitizer.redact("Git patch execution failed at stage " + stage + ": " + exception.getMessage());
            String detail = "stage=" + stage
                    + ", errorType=" + exception.getClass().getSimpleName()
                    + ", branch=" + branch
                    + ", cloneUrlHost=" + safeRemoteLabel(cloneUrl);
            return isRetryableExecutionStage(stage)
                    ? GitPatchExecutionResult.retryableFailure(message, detail)
                    : GitPatchExecutionResult.failure(message, detail);
        } finally {
            deleteQuietly(workDir);
        }
    }

    boolean isRetryableExecutionStage(String stage) {
        return "create-temp-dir".equals(stage)
                || "clone".equals(stage)
                || "commit".equals(stage)
                || "push".equals(stage);
    }

    private void validateRequest(GitPatchExecutionRequest request) {
        if (!StringUtils.hasText(request.getCloneUrl())
                || !StringUtils.hasText(request.getBranch())
                || !StringUtils.hasText(request.getPatch())
                || !StringUtils.hasText(request.getToken())) {
            throw new IllegalArgumentException("cloneUrl, branch, patch and token are required");
        }
        validateValidationCommandPolicy(request);
    }

    private void enforcePatchScope(GitPatchExecutionRequest request) {
        if (request.getAllowedPaths() == null || request.getAllowedPaths().isEmpty()) {
            throw new IllegalArgumentException("allowedPaths are required for patch execution");
        }
        fixPatchScopeValidator.validate(request.getPatch(), request.getAllowedPaths());
    }

    private void validateValidationCommandPolicy(GitPatchExecutionRequest request) {
        if (request == null || !StringUtils.hasText(request.getValidationCommand())) {
            return;
        }
        ValidationCommand validationCommand = parseValidationCommand(request.getValidationCommand());
        boolean buildValidationCommand = isBuildValidationCommand(validationCommand);
        ValidationExecutionMode executionMode = resolveValidationExecutionMode(request.getValidationExecutionMode());
        if (buildValidationCommand && !request.isAllowBuildValidationCommands()) {
            throw new IllegalArgumentException(
                    "Validation command may execute PR code and is disabled by default. "
                            + "Set codepilot.github.fix-validation-allow-build-commands=true only inside an isolated sandbox."
            );
        }
        if (buildValidationCommand && executionMode != ValidationExecutionMode.DOCKER) {
            throw new IllegalArgumentException(
                    "Build validation commands require Docker sandbox execution mode. "
                            + "Set codepilot.github.fix-validation-execution-mode=docker and configure "
                            + "codepilot.github.fix-validation-docker-image."
            );
        }
        if (executionMode == ValidationExecutionMode.DOCKER) {
            validateDockerSandboxConfig(request.getValidationDockerImage(), request.getValidationDockerNetwork());
        }
    }

    private void applyPatch(Git git, String patch) throws Exception {
        try (ByteArrayInputStream inputStream = new ByteArrayInputStream(patch.getBytes(StandardCharsets.UTF_8))) {
            git.apply().setPatch(inputStream).call();
        }
    }

    private GitPatchExecutionResult validate(
            Path workDir,
            String validationCommand,
            List<String> allowedValidationCommands,
            boolean allowBuildValidationCommands,
            ValidationExecutionMode validationExecutionMode,
            String validationDockerImage,
            String validationDockerNetwork,
            boolean inheritValidationEnvironment,
            int timeoutSeconds
    )
            throws IOException, InterruptedException {
        if (!StringUtils.hasText(validationCommand)) {
            return GitPatchExecutionResult.success(null, "Validation skipped.", null);
        }
        ValidationCommand parsedCommand;
        try {
            parsedCommand = parseValidationCommand(validationCommand);
        } catch (IllegalArgumentException exception) {
            return GitPatchExecutionResult.failure(
                    "Validation command is unsafe.",
                    SensitiveDataSanitizer.redact(exception.getMessage())
            );
        }
        if (!isValidationCommandAllowed(parsedCommand, allowedValidationCommands)) {
            return GitPatchExecutionResult.failure(
                    "Validation command is not allowed.",
                    SensitiveDataSanitizer.redact("command=" + parsedCommand.normalized()
                            + ", allowedCommands=" + allowedValidationCommands
                    )
            );
        }
        boolean buildValidationCommand = isBuildValidationCommand(parsedCommand);
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
                sanitizeValidationEnvironment(processBuilder.environment(), workDir);
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
                        sanitizedValidationOutput(outputFile)
                );
            }
            String output = sanitizedValidationOutput(outputFile);
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
            validateDockerSandboxConfig(validationDockerImage, validationDockerNetwork);
        } catch (IllegalArgumentException exception) {
            return GitPatchExecutionResult.failure(
                    "Docker sandbox validation is not configured safely.",
                    SensitiveDataSanitizer.redact(exception.getMessage())
            );
        }

        String containerName = dockerValidationContainerName();
        String dockerImage = normalizeDockerImage(validationDockerImage);
        String dockerNetwork = normalizeDockerNetwork(validationDockerNetwork);
        Path outputFile = Files.createTempFile("codepilot-validation-docker-", ".log");
        try {
            DockerCommandResult createResult = runDockerCommand(
                    buildDockerCreateCommand(containerName, dockerImage, dockerNetwork, parsedCommand.tokens()),
                    outputFile,
                    Math.min(timeoutSeconds, 60)
            );
            if (!createResult.success()) {
                return dockerValidationFailure("Docker sandbox container creation failed.", createResult, outputFile);
            }

            DockerCommandResult copyResult = runDockerCommand(
                    buildDockerCopyCommand(workDir, containerName),
                    outputFile,
                    timeoutSeconds
            );
            if (!copyResult.success()) {
                return dockerValidationFailure("Docker sandbox workspace copy failed.", copyResult, outputFile);
            }

            DockerCommandResult startResult = runDockerCommand(
                    buildDockerStartCommand(containerName),
                    outputFile,
                    timeoutSeconds
            );
            String output = sanitizedValidationOutput(outputFile);
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
        String detail = sanitizedValidationOutput(outputFile);
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
        sanitizeDockerClientEnvironment(processBuilder.environment());
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

    List<String> buildDockerCreateCommand(
            String containerName,
            String dockerImage,
            String dockerNetwork,
            List<String> validationTokens
    ) {
        List<String> command = new ArrayList<>();
        command.add(DOCKER_EXECUTABLE);
        command.add("create");
        command.add("--name");
        command.add(containerName);
        command.add("--label");
        command.add("codepilot.validation=true");
        command.add("--network");
        command.add(normalizeDockerNetwork(dockerNetwork));
        command.add("--workdir");
        command.add(DOCKER_CONTAINER_WORKDIR);
        command.add("--cap-drop");
        command.add("ALL");
        command.add("--security-opt");
        command.add("no-new-privileges");
        command.add("--pids-limit");
        command.add("256");
        command.add("--memory");
        command.add("1g");
        command.add("--cpus");
        command.add("1.0");
        command.add(normalizeDockerImage(dockerImage));
        command.addAll(validationTokens == null ? List.of() : validationTokens);
        return List.copyOf(command);
    }

    List<String> buildDockerCopyCommand(Path workDir, String containerName) {
        return List.of(
                DOCKER_EXECUTABLE,
                "cp",
                dockerCopySource(workDir),
                containerName + ":" + DOCKER_CONTAINER_WORKDIR
        );
    }

    List<String> buildDockerStartCommand(String containerName) {
        return List.of(DOCKER_EXECUTABLE, "start", "--attach", containerName);
    }

    List<String> buildDockerRemoveCommand(String containerName) {
        return List.of(DOCKER_EXECUTABLE, "rm", "--force", containerName);
    }

    private void cleanupDockerContainer(String containerName) {
        try {
            ProcessBuilder processBuilder = new ProcessBuilder(buildDockerRemoveCommand(containerName));
            sanitizeDockerClientEnvironment(processBuilder.environment());
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

    String dockerCopySource(Path workDir) {
        Path normalizedWorkDir = workDir.toAbsolutePath().normalize();
        return normalizedWorkDir.toString().replace('\\', '/') + "/.";
    }

    String dockerValidationContainerName() {
        return "codepilot-validation-" + UUID.randomUUID().toString().replace("-", "");
    }

    private void validateDockerSandboxConfig(String dockerImage, String dockerNetwork) {
        if (!isSafeDockerImage(dockerImage)) {
            throw new IllegalArgumentException(
                    "Docker validation mode requires a safe codepilot.github.fix-validation-docker-image value"
            );
        }
        if (!isSafeDockerNetwork(dockerNetwork)) {
            throw new IllegalArgumentException(
                    "Docker validation mode requires a safe codepilot.github.fix-validation-docker-network value"
            );
        }
    }

    private boolean isSafeDockerImage(String dockerImage) {
        String normalized = normalizeDockerImage(dockerImage);
        return StringUtils.hasText(normalized)
                && !normalized.startsWith("-")
                && !normalized.contains("..")
                && SAFE_DOCKER_IMAGE_PATTERN.matcher(normalized).matches();
    }

    private boolean isSafeDockerNetwork(String dockerNetwork) {
        String normalized = normalizeDockerNetwork(dockerNetwork);
        return StringUtils.hasText(normalized)
                && !normalized.startsWith("-")
                && !normalized.contains("..")
                && SAFE_DOCKER_NETWORK_PATTERN.matcher(normalized).matches();
    }

    private String normalizeDockerImage(String dockerImage) {
        return dockerImage == null ? "" : dockerImage.trim();
    }

    private String normalizeDockerNetwork(String dockerNetwork) {
        if (!StringUtils.hasText(dockerNetwork)) {
            return DEFAULT_DOCKER_NETWORK;
        }
        return dockerNetwork.trim();
    }

    private ValidationExecutionMode resolveValidationExecutionMode(ValidationExecutionMode validationExecutionMode) {
        return validationExecutionMode == null ? ValidationExecutionMode.LOCAL : validationExecutionMode;
    }

    void sanitizeDockerClientEnvironment(Map<String, String> environment) {
        Map<String, String> inheritedSafeValues = new HashMap<>();
        keepEnvironmentValue(environment, inheritedSafeValues, "PATH");
        keepEnvironmentValue(environment, inheritedSafeValues, "Path");
        keepEnvironmentValue(environment, inheritedSafeValues, "SystemRoot");
        keepEnvironmentValue(environment, inheritedSafeValues, "WINDIR");
        keepEnvironmentValue(environment, inheritedSafeValues, "HOME");
        keepEnvironmentValue(environment, inheritedSafeValues, "USERPROFILE");
        keepEnvironmentValue(environment, inheritedSafeValues, "DOCKER_HOST");
        keepEnvironmentValue(environment, inheritedSafeValues, "DOCKER_CONTEXT");
        keepEnvironmentValue(environment, inheritedSafeValues, "DOCKER_TLS_VERIFY");
        keepEnvironmentValue(environment, inheritedSafeValues, "DOCKER_CERT_PATH");
        keepEnvironmentValue(environment, inheritedSafeValues, "DOCKER_CONFIG");
        environment.clear();
        environment.putAll(inheritedSafeValues);
    }

    private record DockerCommandResult(int exitCode, boolean timedOut) {

        private boolean success() {
            return !timedOut && exitCode == 0;
        }
    }

    private int resolveValidationTimeoutSeconds(GitPatchExecutionRequest request) {
        if (request == null || request.getValidationTimeoutSeconds() <= 0) {
            return DEFAULT_VALIDATION_TIMEOUT_SECONDS;
        }
        return request.getValidationTimeoutSeconds();
    }

    private String readValidationOutput(Path outputFile) throws IOException {
        if (outputFile == null || !Files.exists(outputFile)) {
            return "";
        }
        return Files.readString(outputFile, StandardCharsets.UTF_8);
    }

    private String truncateValidationOutput(String output) {
        if (output.length() <= MAX_VALIDATION_OUTPUT_CHARS) {
            return output;
        }
        return SensitiveDataSanitizer.truncatePreservingRedactionMarker(output, MAX_VALIDATION_OUTPUT_CHARS)
                + "\n... output truncated ...";
    }

    String sanitizedValidationOutput(Path outputFile) throws IOException {
        return truncateValidationOutput(SensitiveDataSanitizer.redact(readValidationOutput(outputFile)));
    }

    String safeRemoteLabel(String cloneUrl) {
        if (!StringUtils.hasText(cloneUrl)) {
            return "unknown";
        }
        try {
            java.net.URI uri = java.net.URI.create(cloneUrl);
            if (StringUtils.hasText(uri.getHost())) {
                return uri.getHost();
            }
        } catch (IllegalArgumentException ignored) {
            // Fall back to a redacted, truncated label for non-URI Git remotes.
        }
        String redacted = SensitiveDataSanitizer.redact(cloneUrl);
        int maxLength = 80;
        return redacted.length() <= maxLength ? redacted : redacted.substring(0, maxLength);
    }

    private List<String> splitCommand(String command) {
        return java.util.Arrays.stream(normalizeCommand(command).split("\\s+"))
                .filter(StringUtils::hasText)
                .toList();
    }

    boolean isValidationCommandAllowed(String validationCommand, List<String> allowedValidationCommands) {
        try {
            return isValidationCommandAllowed(parseValidationCommand(validationCommand), allowedValidationCommands);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    boolean isBuildValidationCommand(String validationCommand) {
        try {
            return isBuildValidationCommand(parseValidationCommand(validationCommand));
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private boolean isValidationCommandAllowed(ValidationCommand validationCommand, List<String> allowedValidationCommands) {
        if (validationCommand == null || allowedValidationCommands == null || allowedValidationCommands.isEmpty()) {
            return false;
        }
        return allowedValidationCommands.stream()
                .filter(StringUtils::hasText)
                .map(this::parseAllowedValidationCommand)
                .filter(java.util.Objects::nonNull)
                .anyMatch(validationCommand::equals);
    }

    private String normalizeCommand(String command) {
        return command == null ? "" : command.trim().replaceAll("\\s+", " ");
    }

    private ValidationCommand parseAllowedValidationCommand(String command) {
        try {
            return parseValidationCommand(command);
        } catch (IllegalArgumentException exception) {
            log.warn("Ignored unsafe allowed validation command, command={}, reason={}",
                    SensitiveDataSanitizer.redact(command),
                    SensitiveDataSanitizer.redact(exception.getMessage()));
            return null;
        }
    }

    private ValidationCommand parseValidationCommand(String command) {
        if (!StringUtils.hasText(command)) {
            throw new IllegalArgumentException("validation command is blank");
        }
        if (command.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("validation command contains control characters");
        }
        List<String> tokens = splitCommand(command);
        if (tokens.isEmpty()) {
            throw new IllegalArgumentException("validation command has no executable");
        }
        String executable = tokens.get(0);
        String executableLowerCase = executable.toLowerCase(java.util.Locale.ROOT);
        if (BLOCKED_VALIDATION_EXECUTABLES.contains(executableLowerCase)) {
            throw new IllegalArgumentException("validation command must not invoke a shell executable");
        }
        if (executable.contains("/") || executable.contains("\\")) {
            throw new IllegalArgumentException("validation command executable must be resolved from PATH, not from a file path");
        }
        for (String token : tokens) {
            if (!SAFE_VALIDATION_TOKEN_PATTERN.matcher(token).matches()) {
                throw new IllegalArgumentException("validation command contains unsafe token: " + token);
            }
            if (token.contains("..")) {
                throw new IllegalArgumentException("validation command must not contain path traversal tokens");
            }
        }
        return new ValidationCommand(List.copyOf(tokens));
    }

    private boolean isBuildValidationCommand(ValidationCommand validationCommand) {
        if (validationCommand == null || validationCommand.tokens().isEmpty()) {
            return false;
        }
        String executable = validationCommand.tokens().get(0).toLowerCase(java.util.Locale.ROOT);
        return BUILD_VALIDATION_EXECUTABLES.contains(executable);
    }

    private record ValidationCommand(List<String> tokens) {

        private String normalized() {
            return String.join(" ", tokens);
        }
    }

    void sanitizeValidationEnvironment(Map<String, String> environment, Path workDir) throws IOException {
        Map<String, String> inheritedSafeValues = new HashMap<>();
        keepEnvironmentValue(environment, inheritedSafeValues, "PATH");
        keepEnvironmentValue(environment, inheritedSafeValues, "Path");
        keepEnvironmentValue(environment, inheritedSafeValues, "SystemRoot");
        keepEnvironmentValue(environment, inheritedSafeValues, "WINDIR");
        environment.clear();
        environment.putAll(inheritedSafeValues);
        Path isolatedHomePath = workDir.resolve(".codepilot-validation-home");
        Path isolatedTempPath = workDir.resolve(".codepilot-validation-tmp");
        Files.createDirectories(isolatedHomePath);
        Files.createDirectories(isolatedTempPath);
        String isolatedHome = isolatedHomePath.toString();
        String isolatedTemp = isolatedTempPath.toString();
        environment.put("HOME", isolatedHome);
        environment.put("USERPROFILE", isolatedHome);
        environment.put("TEMP", isolatedTemp);
        environment.put("TMP", isolatedTemp);
        environment.put("TMPDIR", isolatedTemp);
    }

    private void keepEnvironmentValue(Map<String, String> source, Map<String, String> target, String key) {
        String value = source.get(key);
        if (StringUtils.hasText(value)) {
            target.put(key, value);
        }
    }

    private void deleteQuietly(Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (var stream = Files.walk(path)) {
            stream.sorted(Comparator.reverseOrder())
                    .forEach(current -> {
                        try {
                            Files.deleteIfExists(current);
                        } catch (IOException ignored) {
                            // Best effort cleanup for temporary clone directories.
                        }
                    });
        } catch (IOException ignored) {
            // Best effort cleanup for temporary clone directories.
        }
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
}

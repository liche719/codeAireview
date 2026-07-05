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
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class JGitPatchExecutor implements GitPatchExecutor {

    private static final int DEFAULT_VALIDATION_TIMEOUT_SECONDS = 300;

    private final FixPatchScopeValidator fixPatchScopeValidator;

    private final ValidationCommandPolicy validationCommandPolicy = new ValidationCommandPolicy();

    private final DockerSandboxCommandFactory dockerSandboxCommandFactory = new DockerSandboxCommandFactory();

    private final ValidationEnvironmentSanitizer validationEnvironmentSanitizer = new ValidationEnvironmentSanitizer();

    private final ValidationOutputSanitizer validationOutputSanitizer = new ValidationOutputSanitizer();

    private final PatchValidationRunner patchValidationRunner = new PatchValidationRunner(
            validationCommandPolicy,
            dockerSandboxCommandFactory,
            validationEnvironmentSanitizer,
            validationOutputSanitizer
    );

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
                GitPatchExecutionResult validationResult = patchValidationRunner.validate(
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
        ValidationCommand validationCommand = validationCommandPolicy.parse(request.getValidationCommand());
        boolean buildValidationCommand = validationCommandPolicy.isBuildValidationCommand(validationCommand);
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
            dockerSandboxCommandFactory.validateConfig(
                    request.getValidationDockerImage(),
                    request.getValidationDockerNetwork()
            );
        }
    }

    private void applyPatch(Git git, String patch) throws Exception {
        try (ByteArrayInputStream inputStream = new ByteArrayInputStream(patch.getBytes(StandardCharsets.UTF_8))) {
            git.apply().setPatch(inputStream).call();
        }
    }

    List<String> buildDockerCreateCommand(
            String containerName,
            String dockerImage,
            String dockerNetwork,
            List<String> validationTokens
    ) {
        return dockerSandboxCommandFactory.buildCreateCommand(containerName, dockerImage, dockerNetwork, validationTokens);
    }

    List<String> buildDockerCopyCommand(Path workDir, String containerName) {
        return dockerSandboxCommandFactory.buildCopyCommand(workDir, containerName);
    }

    List<String> buildDockerStartCommand(String containerName) {
        return dockerSandboxCommandFactory.buildStartCommand(containerName);
    }

    List<String> buildDockerRemoveCommand(String containerName) {
        return dockerSandboxCommandFactory.buildRemoveCommand(containerName);
    }

    String dockerCopySource(Path workDir) {
        return dockerSandboxCommandFactory.dockerCopySource(workDir);
    }

    String dockerValidationContainerName() {
        return dockerSandboxCommandFactory.validationContainerName();
    }

    private ValidationExecutionMode resolveValidationExecutionMode(ValidationExecutionMode validationExecutionMode) {
        return validationExecutionMode == null ? ValidationExecutionMode.LOCAL : validationExecutionMode;
    }

    void sanitizeDockerClientEnvironment(Map<String, String> environment) {
        validationEnvironmentSanitizer.sanitizeDockerClientEnvironment(environment);
    }

    private int resolveValidationTimeoutSeconds(GitPatchExecutionRequest request) {
        if (request == null || request.getValidationTimeoutSeconds() <= 0) {
            return DEFAULT_VALIDATION_TIMEOUT_SECONDS;
        }
        return request.getValidationTimeoutSeconds();
    }

    String sanitizedValidationOutput(Path outputFile) throws IOException {
        return validationOutputSanitizer.sanitizedOutput(outputFile);
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

    boolean isValidationCommandAllowed(String validationCommand, List<String> allowedValidationCommands) {
        return validationCommandPolicy.isValidationCommandAllowed(validationCommand, allowedValidationCommands);
    }

    boolean isBuildValidationCommand(String validationCommand) {
        return validationCommandPolicy.isBuildValidationCommand(validationCommand);
    }

    void sanitizeValidationEnvironment(Map<String, String> environment, Path workDir) throws IOException {
        validationEnvironmentSanitizer.sanitizeValidationEnvironment(environment, workDir);
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

}

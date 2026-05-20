package com.codepilot.module.command.git;

import lombok.extern.slf4j.Slf4j;
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
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class JGitPatchExecutor implements GitPatchExecutor {

    private static final int DEFAULT_VALIDATION_TIMEOUT_SECONDS = 300;

    private static final int MAX_VALIDATION_OUTPUT_CHARS = 12000;

    @Override
    public GitPatchExecutionResult execute(GitPatchExecutionRequest request) {
        Path workDir = null;
        String stage = "validate-request";
        try {
            validateRequest(request);
            String branch = request.getBranch();
            String cloneUrl = request.getCloneUrl();
            boolean dryRun = request.isDryRun();

            stage = "create-temp-dir";
            workDir = Files.createTempDirectory("codepilot-fix-");
            log.info("Git patch execution started, stage={}, branch={}, dryRun={}, hasValidationCommand={}, cloneUrl={}",
                    stage, branch, dryRun, StringUtils.hasText(request.getValidationCommand()), cloneUrl);

            UsernamePasswordCredentialsProvider credentials = new UsernamePasswordCredentialsProvider(
                    "x-access-token",
                    request.getToken()
            );

            stage = "clone";
            log.info("Git patch execution stage start, stage={}, branch={}, cloneUrl={}", stage, branch, cloneUrl);
            try (Git git = Git.cloneRepository()
                    .setURI(request.getCloneUrl())
                    .setDirectory(workDir.toFile())
                    .setBranch(request.getBranch())
                    .setCredentialsProvider(credentials)
                    .call()) {
                log.info("Git patch execution stage success, stage={}, branch={}, workDir={}", stage, branch, workDir);

                stage = "apply";
                log.info("Git patch execution stage start, stage={}, branch={}", stage, branch);
                applyPatch(git, request.getPatch());
                log.info("Git patch execution stage success, stage={}, branch={}", stage, branch);

                stage = "validate";
                int validationTimeoutSeconds = resolveValidationTimeoutSeconds(request);
                log.info("Git patch execution stage start, stage={}, branch={}, validationCommand={}, timeoutSeconds={}",
                        stage, branch, request.getValidationCommand(), validationTimeoutSeconds);
                GitPatchExecutionResult validationResult = validate(
                        workDir,
                        request.getValidationCommand(),
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
            log.warn("Git patch execution failed, stage={}, branch={}, cloneUrl={}, dryRun={}, errorType={}, message={}",
                    stage,
                    branch,
                    cloneUrl,
                    request != null && request.isDryRun(),
                    exception.getClass().getSimpleName(),
                    exception.getMessage(),
                    exception);
            return GitPatchExecutionResult.failure(
                    "Git patch execution failed at stage " + stage + ": " + exception.getMessage(),
                    "stage=" + stage
                            + ", errorType=" + exception.getClass().getSimpleName()
                            + ", branch=" + branch
                            + ", cloneUrl=" + cloneUrl
            );
        } finally {
            deleteQuietly(workDir);
        }
    }

    private void validateRequest(GitPatchExecutionRequest request) {
        if (!StringUtils.hasText(request.getCloneUrl())
                || !StringUtils.hasText(request.getBranch())
                || !StringUtils.hasText(request.getPatch())
                || !StringUtils.hasText(request.getToken())) {
            throw new IllegalArgumentException("cloneUrl, branch, patch and token are required");
        }
    }

    private void applyPatch(Git git, String patch) throws Exception {
        try (ByteArrayInputStream inputStream = new ByteArrayInputStream(patch.getBytes(StandardCharsets.UTF_8))) {
            git.apply().setPatch(inputStream).call();
        }
    }

    private GitPatchExecutionResult validate(Path workDir, String validationCommand, int timeoutSeconds)
            throws IOException, InterruptedException {
        if (!StringUtils.hasText(validationCommand)) {
            return GitPatchExecutionResult.success(null, "Validation skipped.", null);
        }
        Path outputFile = Files.createTempFile("codepilot-validation-", ".log");
        try {
            ProcessBuilder processBuilder = new ProcessBuilder(splitCommand(validationCommand));
            processBuilder.directory(workDir.toFile());
            processBuilder.redirectErrorStream(true);
            processBuilder.redirectOutput(outputFile.toFile());
            Process process = processBuilder.start();
            boolean completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
                return GitPatchExecutionResult.failure(
                        "Validation command timed out after " + timeoutSeconds + " seconds.",
                        readValidationOutput(outputFile)
                );
            }
            String output = readValidationOutput(outputFile);
            if (process.exitValue() != 0) {
                return GitPatchExecutionResult.failure("Validation command failed with exit code " + process.exitValue(), output);
            }
            return GitPatchExecutionResult.success(null, "Validation command passed.", output);
        } finally {
            deleteFileQuietly(outputFile);
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
        String output = Files.readString(outputFile, StandardCharsets.UTF_8);
        if (output.length() <= MAX_VALIDATION_OUTPUT_CHARS) {
            return output;
        }
        return output.substring(0, MAX_VALIDATION_OUTPUT_CHARS) + "\n... output truncated ...";
    }

    private List<String> splitCommand(String command) {
        return java.util.Arrays.stream(command.trim().split("\\s+"))
                .filter(StringUtils::hasText)
                .toList();
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

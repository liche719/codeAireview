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

@Slf4j
@Component
public class JGitPatchExecutor implements GitPatchExecutor {

    private static final int PROCESS_TIMEOUT_SECONDS = 120;

    @Override
    public GitPatchExecutionResult execute(GitPatchExecutionRequest request) {
        Path workDir = null;
        try {
            validateRequest(request);
            workDir = Files.createTempDirectory("codepilot-fix-");
            UsernamePasswordCredentialsProvider credentials = new UsernamePasswordCredentialsProvider(
                    "x-access-token",
                    request.getToken()
            );

            try (Git git = Git.cloneRepository()
                    .setURI(request.getCloneUrl())
                    .setDirectory(workDir.toFile())
                    .setBranch(request.getBranch())
                    .setCredentialsProvider(credentials)
                    .call()) {
                applyPatch(git, request.getPatch());

                GitPatchExecutionResult validationResult = validate(workDir, request.getValidationCommand());
                if (!validationResult.isSuccess()) {
                    return validationResult;
                }

                if (request.isDryRun()) {
                    return GitPatchExecutionResult.success(null, "Dry-run patch validation passed.", validationResult.getDetail());
                }

                git.add().addFilepattern(".").call();
                if (git.status().call().isClean()) {
                    return GitPatchExecutionResult.failure("Patch applied but no file changed.", null);
                }
                String commitSha = git.commit()
                        .setMessage(request.getCommitMessage())
                        .call()
                        .getName();
                git.push()
                        .setCredentialsProvider(credentials)
                        .setRefSpecs(new RefSpec("HEAD:refs/heads/" + request.getBranch()))
                        .call();
                return GitPatchExecutionResult.success(commitSha, "Patch committed and pushed.", validationResult.getDetail());
            }
        } catch (Exception exception) {
            log.warn("Git patch execution failed, message={}", exception.getMessage());
            return GitPatchExecutionResult.failure(exception.getMessage(), null);
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

    private GitPatchExecutionResult validate(Path workDir, String validationCommand) throws IOException, InterruptedException {
        if (!StringUtils.hasText(validationCommand)) {
            return GitPatchExecutionResult.success(null, "Validation skipped.", null);
        }
        ProcessBuilder processBuilder = new ProcessBuilder(splitCommand(validationCommand));
        processBuilder.directory(workDir.toFile());
        processBuilder.redirectErrorStream(true);
        Process process = processBuilder.start();
        boolean completed = process.waitFor(PROCESS_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!completed) {
            process.destroyForcibly();
            return GitPatchExecutionResult.failure("Validation command timed out.", output);
        }
        if (process.exitValue() != 0) {
            return GitPatchExecutionResult.failure("Validation command failed with exit code " + process.exitValue(), output);
        }
        return GitPatchExecutionResult.success(null, "Validation command passed.", output);
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
}

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
import java.util.HashMap;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

@Slf4j
@Component
public class JGitPatchExecutor implements GitPatchExecutor {

    private static final int DEFAULT_VALIDATION_TIMEOUT_SECONDS = 300;

    private static final int MAX_VALIDATION_OUTPUT_CHARS = 12000;

    private static final Pattern SAFE_VALIDATION_TOKEN_PATTERN = Pattern.compile("[A-Za-z0-9._:/=+%,~-]+");

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
                log.info("Git patch execution stage start, stage={}, branch={}, validationCommand={}, timeoutSeconds={}",
                        stage, branch, request.getValidationCommand(), validationTimeoutSeconds);
                GitPatchExecutionResult validationResult = validate(
                        workDir,
                        request.getValidationCommand(),
                        request.getAllowedValidationCommands(),
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

    private GitPatchExecutionResult validate(
            Path workDir,
            String validationCommand,
            List<String> allowedValidationCommands,
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
                    exception.getMessage()
            );
        }
        if (!isValidationCommandAllowed(parsedCommand, allowedValidationCommands)) {
            return GitPatchExecutionResult.failure(
                    "Validation command is not allowed.",
                    "command=" + parsedCommand.normalized()
                            + ", allowedCommands=" + allowedValidationCommands
            );
        }
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
            log.warn("Ignored unsafe allowed validation command, command={}, reason={}", command, exception.getMessage());
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

    private record ValidationCommand(List<String> tokens) {

        private String normalized() {
            return String.join(" ", tokens);
        }
    }

    private void sanitizeValidationEnvironment(Map<String, String> environment, Path workDir) {
        Map<String, String> inheritedSafeValues = new HashMap<>();
        keepEnvironmentValue(environment, inheritedSafeValues, "PATH");
        keepEnvironmentValue(environment, inheritedSafeValues, "Path");
        keepEnvironmentValue(environment, inheritedSafeValues, "SystemRoot");
        keepEnvironmentValue(environment, inheritedSafeValues, "WINDIR");
        keepEnvironmentValue(environment, inheritedSafeValues, "TEMP");
        keepEnvironmentValue(environment, inheritedSafeValues, "TMP");
        environment.clear();
        environment.putAll(inheritedSafeValues);
        String isolatedHome = workDir.resolve(".codepilot-validation-home").toString();
        environment.put("HOME", isolatedHome);
        environment.put("USERPROFILE", isolatedHome);
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

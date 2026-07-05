package com.codepilot.module.command.git;

import com.codepilot.common.util.SensitiveDataSanitizer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

@Slf4j
class ValidationCommandPolicy {

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

    boolean isValidationCommandAllowed(String validationCommand, List<String> allowedValidationCommands) {
        try {
            return isValidationCommandAllowed(parse(validationCommand), allowedValidationCommands);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    boolean isBuildValidationCommand(String validationCommand) {
        try {
            return isBuildValidationCommand(parse(validationCommand));
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    boolean isValidationCommandAllowed(ValidationCommand validationCommand, List<String> allowedValidationCommands) {
        if (validationCommand == null || allowedValidationCommands == null || allowedValidationCommands.isEmpty()) {
            return false;
        }
        return allowedValidationCommands.stream()
                .filter(StringUtils::hasText)
                .map(this::parseAllowedValidationCommand)
                .filter(java.util.Objects::nonNull)
                .anyMatch(validationCommand::equals);
    }

    ValidationCommand parse(String command) {
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

    boolean isBuildValidationCommand(ValidationCommand validationCommand) {
        if (validationCommand == null || validationCommand.tokens().isEmpty()) {
            return false;
        }
        String executable = validationCommand.tokens().get(0).toLowerCase(java.util.Locale.ROOT);
        return BUILD_VALIDATION_EXECUTABLES.contains(executable);
    }

    private List<String> splitCommand(String command) {
        return java.util.Arrays.stream(normalizeCommand(command).split("\\s+"))
                .filter(StringUtils::hasText)
                .toList();
    }

    private String normalizeCommand(String command) {
        return command == null ? "" : command.trim().replaceAll("\\s+", " ");
    }

    private ValidationCommand parseAllowedValidationCommand(String command) {
        try {
            return parse(command);
        } catch (IllegalArgumentException exception) {
            log.warn("Ignored unsafe allowed validation command, command={}, reason={}",
                    SensitiveDataSanitizer.redact(command),
                    SensitiveDataSanitizer.redact(exception.getMessage()));
            return null;
        }
    }
}

record ValidationCommand(List<String> tokens) {

    String normalized() {
        return String.join(" ", tokens);
    }
}

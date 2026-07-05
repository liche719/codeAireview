package com.codepilot.module.command.git;

import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

class ValidationEnvironmentSanitizer {

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

    private void keepEnvironmentValue(Map<String, String> source, Map<String, String> target, String key) {
        String value = source.get(key);
        if (StringUtils.hasText(value)) {
            target.put(key, value);
        }
    }
}

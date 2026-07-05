package com.codepilot.module.command.git;

import org.springframework.util.StringUtils;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

class DockerSandboxCommandFactory {

    private static final String DOCKER_EXECUTABLE = "docker";

    private static final String DOCKER_CONTAINER_WORKDIR = "/workspace";

    private static final String DEFAULT_DOCKER_NETWORK = "none";

    private static final Pattern SAFE_DOCKER_IMAGE_PATTERN = Pattern.compile("[A-Za-z0-9._:/@-]+");

    private static final Pattern SAFE_DOCKER_NETWORK_PATTERN = Pattern.compile("[A-Za-z0-9._-]+");

    List<String> buildCreateCommand(
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

    List<String> buildCopyCommand(Path workDir, String containerName) {
        return List.of(
                DOCKER_EXECUTABLE,
                "cp",
                dockerCopySource(workDir),
                containerName + ":" + DOCKER_CONTAINER_WORKDIR
        );
    }

    List<String> buildStartCommand(String containerName) {
        return List.of(DOCKER_EXECUTABLE, "start", "--attach", containerName);
    }

    List<String> buildRemoveCommand(String containerName) {
        return List.of(DOCKER_EXECUTABLE, "rm", "--force", containerName);
    }

    String dockerCopySource(Path workDir) {
        Path normalizedWorkDir = workDir.toAbsolutePath().normalize();
        return normalizedWorkDir.toString().replace('\\', '/') + "/.";
    }

    String validationContainerName() {
        return "codepilot-validation-" + UUID.randomUUID().toString().replace("-", "");
    }

    void validateConfig(String dockerImage, String dockerNetwork) {
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

    String normalizeDockerImage(String dockerImage) {
        return dockerImage == null ? "" : dockerImage.trim();
    }

    String normalizeDockerNetwork(String dockerNetwork) {
        if (!StringUtils.hasText(dockerNetwork)) {
            return DEFAULT_DOCKER_NETWORK;
        }
        return dockerNetwork.trim();
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
}

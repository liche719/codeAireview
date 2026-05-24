package com.codepilot.infrastructure.docker;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class DockerRuntimeHardeningTest {

    @Test
    void shouldKeepRuntimeImageMinimalAndNonRoot() throws IOException {
        String dockerfile = Files.readString(Path.of("Dockerfile"), StandardCharsets.UTF_8);
        String runtimeStage = runtimeStage(dockerfile);

        assertThat(dockerfile)
                .contains("FROM mcr.microsoft.com/openjdk/jdk:21-ubuntu AS build")
                .contains("apt-get install -y --no-install-recommends maven");
        assertThat(runtimeStage)
                .contains("FROM eclipse-temurin:21-jre")
                .contains("apt-get install -y --no-install-recommends ca-certificates git")
                .contains("USER codepilot")
                .doesNotContain("maven")
                .doesNotContain("/root/.m2");
    }

    @Test
    void shouldNotMountBuildToolCacheIntoServerRuntimeContainer() throws IOException {
        String compose = Files.readString(Path.of("docker-compose.server.yml"), StandardCharsets.UTF_8);

        assertThat(compose)
                .doesNotContain("maven-cache")
                .doesNotContain("/root/.m2");
    }

    private String runtimeStage(String dockerfile) {
        int index = dockerfile.lastIndexOf("\nFROM ");
        assertThat(index).as("Dockerfile should use a multi-stage build").isGreaterThan(0);
        return dockerfile.substring(index + 1);
    }
}

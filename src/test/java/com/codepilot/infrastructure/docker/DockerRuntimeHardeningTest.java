package com.codepilot.infrastructure.docker;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class DockerRuntimeHardeningTest {

    /**
     * 运行镜像的约束。
     *
     * <h2>为什么不再要求 {@code eclipse-temurin:21-jre}</h2>
     * 原测试断言运行阶段必须是 JRE 镜像。改成复用构建阶段的
     * {@code mcr.microsoft.com/openjdk/jdk:21-ubuntu} 是**有意的取舍**，理由是一次真实事故：
     * 服务器上 Docker Hub 只能走镜像加速器，而加速器返回 {@code 502 Bad Gateway}，
     * 导致 {@code docker compose up --build} 在解析 {@code eclipse-temurin:21-jre-jammy} 时
     * 直接失败——**构建阶段用的 mcr 可直连，运行阶段却依赖一条反复失败的路径**，
     * 同一个 Dockerfile 里两个阶段的可达性完全不同。
     *
     * <p>代价：JDK 镜像比 JRE 大约 200MB。收益：Dockerfile 不再依赖 Docker Hub，
     * 镜像源抖动不会让一个与镜像源无关的改动部署失败。</p>
     *
     * <p>仍然保留的约束（这些才真正重要）：运行阶段不能是构建阶段本身、
     * 不能带构建工具、必须以非 root 运行。</p>
     */
    @Test
    void shouldKeepRuntimeImageNonRootAndFreeOfBuildTooling() throws IOException {
        String dockerfile = Files.readString(Path.of("Dockerfile"), StandardCharsets.UTF_8);
        String runtimeStage = runtimeStage(dockerfile);

        assertThat(dockerfile)
                .contains("FROM mcr.microsoft.com/openjdk/jdk:21-ubuntu AS build")
                .contains("apt-get install -y --no-install-recommends maven");
        assertThat(runtimeStage)
                // 基础镜像必须来自可直连的 mcr：Docker Hub 在服务器上只能走加速器。
                .contains("FROM mcr.microsoft.com/openjdk/jdk:21-ubuntu")
                .doesNotContain("AS build")
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

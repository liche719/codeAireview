package com.codepilot.module.command.service.impl;

import com.codepilot.module.command.entity.PrCommandTask;
import com.codepilot.module.command.config.GithubCommandProperties;
import com.codepilot.module.review.entity.ReviewTask;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PrCommandTaskServiceImplTest {

    @Test
    void shouldRequireSameHeadShaBeforeReusingReviewTaskForFix() {
        PrCommandTaskServiceImpl service = new PrCommandTaskServiceImpl(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
        PrCommandTask commandTask = new PrCommandTask();
        commandTask.setHeadSha("abc123");
        ReviewTask reviewTask = new ReviewTask();
        reviewTask.setHeadSha("ABC123");

        assertThat(service.hasSameHeadSha(commandTask, reviewTask)).isTrue();

        reviewTask.setHeadSha("def456");
        assertThat(service.hasSameHeadSha(commandTask, reviewTask)).isFalse();

        reviewTask.setHeadSha(null);
        assertThat(service.hasSameHeadSha(commandTask, reviewTask)).isFalse();
    }

    @Test
    void shouldAllowPatchOnlyForSelectedIssueFile() {
        PrCommandTaskServiceImpl service = serviceWithDefaultProperties();
        String patch = """
                diff --git a/src/main/java/Demo.java b/src/main/java/Demo.java
                --- a/src/main/java/Demo.java
                +++ b/src/main/java/Demo.java
                @@ -1 +1 @@
                -old
                +new
                """;

        var stats = service.validatePatchScope(patch, Set.of("src/main/java/Demo.java"));

        assertThat(stats.filesChanged()).isEqualTo(1);
        assertThat(stats.changedLines()).isEqualTo(2);
        assertThat(stats.paths()).containsExactly("src/main/java/Demo.java");
    }

    @Test
    void shouldRejectPatchForUnselectedFile() {
        PrCommandTaskServiceImpl service = serviceWithDefaultProperties();
        String patch = """
                diff --git a/src/main/java/Other.java b/src/main/java/Other.java
                --- a/src/main/java/Other.java
                +++ b/src/main/java/Other.java
                @@ -1 +1 @@
                -old
                +new
                """;

        assertThatThrownBy(() -> service.validatePatchScope(patch, Set.of("src/main/java/Demo.java")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("自动修复只能修改被选中问题所在文件");
    }

    @Test
    void shouldRejectPatchForSensitivePathEvenWhenSelected() {
        PrCommandTaskServiceImpl service = serviceWithDefaultProperties();
        String patch = """
                diff --git a/.github/workflows/deploy.yml b/.github/workflows/deploy.yml
                --- a/.github/workflows/deploy.yml
                +++ b/.github/workflows/deploy.yml
                @@ -1 +1 @@
                -old
                +new
                """;

        assertThatThrownBy(() -> service.validatePatchScope(patch, Set.of(".github/workflows/deploy.yml")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("自动修复不允许修改敏感路径");
    }

    @Test
    void shouldRejectPatchWithoutFilePathHeaders() {
        PrCommandTaskServiceImpl service = serviceWithDefaultProperties();
        String patch = """
                @@ -1 +1 @@
                -old
                +new
                """;

        assertThatThrownBy(() -> service.validatePatchScope(patch, Set.of("src/main/java/Demo.java")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("补丁没有声明被修改的文件路径");
    }

    @Test
    void shouldRejectPatchThatExceedsChangedLineLimit() {
        GithubCommandProperties properties = new GithubCommandProperties();
        properties.setFixMaxChangedLines(1);
        PrCommandTaskServiceImpl service = serviceWithProperties(properties);
        String patch = """
                diff --git a/src/main/java/Demo.java b/src/main/java/Demo.java
                --- a/src/main/java/Demo.java
                +++ b/src/main/java/Demo.java
                @@ -1 +1 @@
                -old
                +new
                """;

        assertThatThrownBy(() -> service.validatePatchScope(patch, Set.of("src/main/java/Demo.java")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("补丁修改的行数过多");
    }

    private PrCommandTaskServiceImpl serviceWithDefaultProperties() {
        return serviceWithProperties(new GithubCommandProperties());
    }

    private PrCommandTaskServiceImpl serviceWithProperties(GithubCommandProperties properties) {
        return new PrCommandTaskServiceImpl(
                properties,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }
}

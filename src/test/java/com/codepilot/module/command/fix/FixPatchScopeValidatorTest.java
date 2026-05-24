package com.codepilot.module.command.fix;

import com.codepilot.module.command.config.GithubCommandProperties;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FixPatchScopeValidatorTest {

    @Test
    void shouldAllowPatchOnlyForSelectedIssueFile() {
        FixPatchScopeValidator validator = validatorWithDefaultProperties();
        String patch = """
                diff --git a/src/main/java/Demo.java b/src/main/java/Demo.java
                --- a/src/main/java/Demo.java
                +++ b/src/main/java/Demo.java
                @@ -1 +1 @@
                -old
                +new
                """;

        FixPatchScopeValidationResult stats = validator.validate(patch, Set.of("src/main/java/Demo.java"));

        assertThat(stats.filesChanged()).isEqualTo(1);
        assertThat(stats.changedLines()).isEqualTo(2);
        assertThat(stats.paths()).containsExactly("src/main/java/Demo.java");
    }

    @Test
    void shouldRejectPatchForUnselectedFile() {
        FixPatchScopeValidator validator = validatorWithDefaultProperties();
        String patch = """
                diff --git a/src/main/java/Other.java b/src/main/java/Other.java
                --- a/src/main/java/Other.java
                +++ b/src/main/java/Other.java
                @@ -1 +1 @@
                -old
                +new
                """;

        assertThatThrownBy(() -> validator.validate(patch, Set.of("src/main/java/Demo.java")))
                .isInstanceOf(NonRetryableFixTaskException.class)
                .hasMessageContaining("Automatic fixes can only modify selected issue files");
    }

    @Test
    void shouldRejectPatchForSensitivePathEvenWhenSelected() {
        FixPatchScopeValidator validator = validatorWithDefaultProperties();
        String patch = """
                diff --git a/.github/workflows/deploy.yml b/.github/workflows/deploy.yml
                --- a/.github/workflows/deploy.yml
                +++ b/.github/workflows/deploy.yml
                @@ -1 +1 @@
                -old
                +new
                """;

        assertThatThrownBy(() -> validator.validate(patch, Set.of(".github/workflows/deploy.yml")))
                .isInstanceOf(NonRetryableFixTaskException.class)
                .hasMessageContaining("Automatic fixes cannot modify sensitive path");
    }

    @Test
    void shouldRejectPatchWithPathTraversal() {
        FixPatchScopeValidator validator = validatorWithDefaultProperties();
        String patch = """
                diff --git a/../secrets.txt b/../secrets.txt
                --- a/../secrets.txt
                +++ b/../secrets.txt
                @@ -1 +1 @@
                -old
                +new
                """;

        assertThatThrownBy(() -> validator.validate(patch, Set.of("../secrets.txt")))
                .isInstanceOf(NonRetryableFixTaskException.class)
                .hasMessageContaining("unsafe file path");
    }

    @Test
    void shouldRejectPatchWithoutFilePathHeaders() {
        FixPatchScopeValidator validator = validatorWithDefaultProperties();
        String patch = """
                @@ -1 +1 @@
                -old
                +new
                """;

        assertThatThrownBy(() -> validator.validate(patch, Set.of("src/main/java/Demo.java")))
                .isInstanceOf(NonRetryableFixTaskException.class)
                .hasMessageContaining("does not declare modified file paths");
    }

    @Test
    void shouldRejectPatchThatExceedsChangedLineLimit() {
        GithubCommandProperties properties = new GithubCommandProperties();
        properties.setFixMaxChangedLines(1);
        FixPatchScopeValidator validator = new FixPatchScopeValidator(properties);
        String patch = """
                diff --git a/src/main/java/Demo.java b/src/main/java/Demo.java
                --- a/src/main/java/Demo.java
                +++ b/src/main/java/Demo.java
                @@ -1 +1 @@
                -old
                +new
                """;

        assertThatThrownBy(() -> validator.validate(patch, Set.of("src/main/java/Demo.java")))
                .isInstanceOf(NonRetryableFixTaskException.class)
                .hasMessageContaining("changes too many lines");
    }

    private FixPatchScopeValidator validatorWithDefaultProperties() {
        return new FixPatchScopeValidator(new GithubCommandProperties());
    }
}

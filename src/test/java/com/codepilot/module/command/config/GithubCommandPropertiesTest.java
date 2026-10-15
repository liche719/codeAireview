package com.codepilot.module.command.config;

import com.codepilot.module.command.git.ValidationExecutionMode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GithubCommandPropertiesTest {

    @Test
    void shouldBindFixValidationSafetyProperties() {
        MapConfigurationPropertySource source = new MapConfigurationPropertySource(Map.ofEntries(
                Map.entry("codepilot.github.fix-validation-command", "mvn -q -DskipTests compile"),
                Map.entry("codepilot.github.fix-allowed-validation-commands[0]", "git diff --check"),
                Map.entry("codepilot.github.fix-allowed-validation-commands[1]", "mvn -q -DskipTests compile"),
                Map.entry("codepilot.github.fix-validation-allow-build-commands", "true"),
                Map.entry("codepilot.github.fix-validation-inherit-environment", "true"),
                Map.entry("codepilot.github.fix-validation-execution-mode", "docker"),
                Map.entry("codepilot.github.fix-validation-docker-image", "maven:3.9-eclipse-temurin-21"),
                Map.entry("codepilot.github.fix-validation-docker-network", "none"),
                Map.entry("codepilot.github.fix-publish-enabled", "true"),
                Map.entry("codepilot.github.fix-merge-enabled", "true"),
                Map.entry("codepilot.github.fix-approval-require-same-user", "false"),
                Map.entry("codepilot.github.fix-branch-prefix", "automation/fix-"),
                Map.entry("codepilot.github.fix-merge-method", "rebase")
        ));

        GithubCommandProperties properties = new Binder(source)
                .bind("codepilot.github", Bindable.of(GithubCommandProperties.class))
                .get();

        assertThat(properties.getFixValidationCommand()).isEqualTo("mvn -q -DskipTests compile");
        assertThat(properties.getFixAllowedValidationCommands())
                .containsExactly("git diff --check", "mvn -q -DskipTests compile");
        assertThat(properties.isFixValidationAllowBuildCommands()).isTrue();
        assertThat(properties.isFixValidationInheritEnvironment()).isTrue();
        assertThat(properties.getFixValidationExecutionMode()).isEqualTo(ValidationExecutionMode.DOCKER);
        assertThat(properties.getFixValidationDockerImage()).isEqualTo("maven:3.9-eclipse-temurin-21");
        assertThat(properties.getFixValidationDockerNetwork()).isEqualTo("none");
        assertThat(properties.isFixPublishEnabled()).isTrue();
        assertThat(properties.isFixMergeEnabled()).isTrue();
        assertThat(properties.isFixApprovalRequireSameUser()).isFalse();
        assertThat(properties.getFixBranchPrefix()).isEqualTo("automation/fix-");
        assertThat(properties.getFixMergeMethod()).isEqualTo("rebase");
    }

    @Test
    void shouldDefaultToSafeGitDiffValidation() {
        GithubCommandProperties properties = new GithubCommandProperties();

        assertThat(properties.getFixValidationCommand()).isEqualTo("git diff --check");
        assertThat(properties.getFixAllowedValidationCommands()).containsExactly("git diff --check");
        assertThat(properties.isFixValidationAllowBuildCommands()).isFalse();
        assertThat(properties.isFixValidationInheritEnvironment()).isFalse();
        assertThat(properties.getFixValidationExecutionMode()).isEqualTo(ValidationExecutionMode.LOCAL);
        assertThat(properties.getFixValidationDockerImage()).isBlank();
        assertThat(properties.getFixValidationDockerNetwork()).isEqualTo("none");
        assertThat(properties.isFixPublishEnabled()).isFalse();
        assertThat(properties.isFixMergeEnabled()).isFalse();
        assertThat(properties.isFixApprovalRequireSameUser()).isTrue();
        assertThat(properties.getFixBranchPrefix()).isEqualTo("codepilot/fix-");
        assertThat(properties.getFixMergeMethod()).isEqualTo("squash");
    }
}

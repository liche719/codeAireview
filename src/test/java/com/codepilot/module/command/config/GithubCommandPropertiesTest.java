package com.codepilot.module.command.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GithubCommandPropertiesTest {

    @Test
    void shouldBindFixValidationSafetyProperties() {
        MapConfigurationPropertySource source = new MapConfigurationPropertySource(Map.of(
                "codepilot.github.fix-validation-command", "mvn -q -DskipTests compile",
                "codepilot.github.fix-allowed-validation-commands[0]", "git diff --check",
                "codepilot.github.fix-allowed-validation-commands[1]", "mvn -q -DskipTests compile",
                "codepilot.github.fix-validation-inherit-environment", "true"
        ));

        GithubCommandProperties properties = new Binder(source)
                .bind("codepilot.github", Bindable.of(GithubCommandProperties.class))
                .get();

        assertThat(properties.getFixValidationCommand()).isEqualTo("mvn -q -DskipTests compile");
        assertThat(properties.getFixAllowedValidationCommands())
                .containsExactly("git diff --check", "mvn -q -DskipTests compile");
        assertThat(properties.isFixValidationInheritEnvironment()).isTrue();
    }

    @Test
    void shouldDefaultToSafeGitDiffValidation() {
        GithubCommandProperties properties = new GithubCommandProperties();

        assertThat(properties.getFixValidationCommand()).isEqualTo("git diff --check");
        assertThat(properties.getFixAllowedValidationCommands()).containsExactly("git diff --check");
        assertThat(properties.isFixValidationInheritEnvironment()).isFalse();
    }
}

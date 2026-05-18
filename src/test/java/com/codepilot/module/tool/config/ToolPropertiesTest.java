package com.codepilot.module.tool.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ToolPropertiesTest {

    @Test
    void shouldBindToolProperties() {
        MapConfigurationPropertySource source = new MapConfigurationPropertySource(Map.of(
                "codepilot.tools.enabled", "false",
                "codepilot.tools.sql-risk-enabled", "true",
                "codepilot.tools.secret-scan-enabled", "false",
                "codepilot.tools.test-suggestion-enabled", "true"
        ));

        ToolProperties properties = new Binder(source)
                .bind("codepilot.tools", Bindable.of(ToolProperties.class))
                .get();

        assertThat(properties.isEnabled()).isFalse();
        assertThat(properties.isSqlRiskEnabled()).isTrue();
        assertThat(properties.isSecretScanEnabled()).isFalse();
        assertThat(properties.isTestSuggestionEnabled()).isTrue();
    }
}

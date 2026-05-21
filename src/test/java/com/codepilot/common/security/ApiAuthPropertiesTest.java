package com.codepilot.common.security;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ApiAuthPropertiesTest {

    @Test
    void shouldBindApiAuthProperties() {
        MapConfigurationPropertySource source = new MapConfigurationPropertySource(Map.of(
                "codepilot.api-auth.enabled", "false",
                "codepilot.api-auth.api-key", "secret",
                "codepilot.api-auth.header-name", "X-Test-Key",
                "codepilot.api-auth.protected-path-patterns[0]", "/internal/**",
                "codepilot.api-auth.exclude-path-patterns[0]", "/internal/health"
        ));

        ApiAuthProperties properties = new Binder(source)
                .bind("codepilot.api-auth", Bindable.of(ApiAuthProperties.class))
                .get();

        assertThat(properties.isEnabled()).isFalse();
        assertThat(properties.getApiKey()).isEqualTo("secret");
        assertThat(properties.getHeaderName()).isEqualTo("X-Test-Key");
        assertThat(properties.getProtectedPathPatterns()).containsExactly("/internal/**");
        assertThat(properties.getExcludePathPatterns()).containsExactly("/internal/health");
    }
}

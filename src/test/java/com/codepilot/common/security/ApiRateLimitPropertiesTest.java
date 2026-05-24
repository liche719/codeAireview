package com.codepilot.common.security;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ApiRateLimitPropertiesTest {

    @Test
    void shouldBindApiRateLimitProperties() {
        MapConfigurationPropertySource source = new MapConfigurationPropertySource(Map.of(
                "codepilot.api-rate-limit.enabled", "false",
                "codepilot.api-rate-limit.max-requests-per-window", "10",
                "codepilot.api-rate-limit.window", "30s",
                "codepilot.api-rate-limit.protected-path-patterns[0]", "/internal/**",
                "codepilot.api-rate-limit.exclude-path-patterns[0]", "/internal/health"
        ));

        ApiRateLimitProperties properties = new Binder(source)
                .bind("codepilot.api-rate-limit", Bindable.of(ApiRateLimitProperties.class))
                .get();

        assertThat(properties.isEnabled()).isFalse();
        assertThat(properties.getMaxRequestsPerWindow()).isEqualTo(10);
        assertThat(properties.getWindow()).isEqualTo(Duration.ofSeconds(30));
        assertThat(properties.getProtectedPathPatterns()).containsExactly("/internal/**");
        assertThat(properties.getExcludePathPatterns()).containsExactly("/internal/health");
    }
}

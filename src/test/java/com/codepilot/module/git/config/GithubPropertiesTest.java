package com.codepilot.module.git.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GithubPropertiesTest {

    @Test
    void shouldBindGithubRateLimitBackoffProperties() {
        MapConfigurationPropertySource source = new MapConfigurationPropertySource(Map.of(
                "codepilot.github.api-base-url", "https://api.github.test",
                "codepilot.github.token", "test-token",
                "codepilot.github.rate-limit-max-attempts", "5",
                "codepilot.github.rate-limit-initial-delay-millis", "250",
                "codepilot.github.rate-limit-backoff-multiplier", "1.5",
                "codepilot.github.rate-limit-max-delay-millis", "3000"
        ));

        GithubProperties properties = new Binder(source)
                .bind("codepilot.github", Bindable.of(GithubProperties.class))
                .get();

        assertThat(properties.getApiBaseUrl()).isEqualTo("https://api.github.test");
        assertThat(properties.getToken()).isEqualTo("test-token");
        assertThat(properties.getRateLimitMaxAttempts()).isEqualTo(5);
        assertThat(properties.getRateLimitInitialDelayMillis()).isEqualTo(250L);
        assertThat(properties.getRateLimitBackoffMultiplier()).isEqualTo(1.5D);
        assertThat(properties.getRateLimitMaxDelayMillis()).isEqualTo(3000L);
    }
}

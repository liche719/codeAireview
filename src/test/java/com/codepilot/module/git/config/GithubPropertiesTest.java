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
        MapConfigurationPropertySource source = new MapConfigurationPropertySource(Map.ofEntries(
                Map.entry("codepilot.github.api-base-url", "https://api.github.test"),
                Map.entry("codepilot.github.auth-mode", "app"),
                Map.entry("codepilot.github.token", "test-token"),
                Map.entry("codepilot.github.app-id", "12345"),
                Map.entry("codepilot.github.app-private-key-base64", "private-key"),
                Map.entry("codepilot.github.app-installation-id", "67890"),
                Map.entry("codepilot.github.app-token-cache-skew-seconds", "90"),
                Map.entry("codepilot.github.rate-limit-max-attempts", "5"),
                Map.entry("codepilot.github.rate-limit-initial-delay-millis", "250"),
                Map.entry("codepilot.github.rate-limit-backoff-multiplier", "1.5"),
                Map.entry("codepilot.github.rate-limit-max-delay-millis", "3000")
        ));

        GithubProperties properties = new Binder(source)
                .bind("codepilot.github", Bindable.of(GithubProperties.class))
                .get();

        assertThat(properties.getApiBaseUrl()).isEqualTo("https://api.github.test");
        assertThat(properties.getAuthMode()).isEqualTo(GithubProperties.AuthMode.APP);
        assertThat(properties.getToken()).isEqualTo("test-token");
        assertThat(properties.getAppId()).isEqualTo("12345");
        assertThat(properties.getAppPrivateKeyBase64()).isEqualTo("private-key");
        assertThat(properties.getAppInstallationId()).isEqualTo(67890L);
        assertThat(properties.getAppTokenCacheSkewSeconds()).isEqualTo(90L);
        assertThat(properties.getRateLimitMaxAttempts()).isEqualTo(5);
        assertThat(properties.getRateLimitInitialDelayMillis()).isEqualTo(250L);
        assertThat(properties.getRateLimitBackoffMultiplier()).isEqualTo(1.5D);
        assertThat(properties.getRateLimitMaxDelayMillis()).isEqualTo(3000L);
    }
}

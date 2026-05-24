package com.codepilot.module.agent.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RagPropertiesTest {

    @Test
    void shouldBindRagCacheProperties() {
        MapConfigurationPropertySource source = new MapConfigurationPropertySource(Map.of(
                "codepilot.rag.cache-enabled", "false",
                "codepilot.rag.cache-max-size", "12",
                "codepilot.rag.cache-ttl-seconds", "30"
        ));

        RagProperties properties = new Binder(source)
                .bind("codepilot.rag", Bindable.of(RagProperties.class))
                .get();

        assertThat(properties.isCacheEnabled()).isFalse();
        assertThat(properties.getCacheMaxSize()).isEqualTo(12);
        assertThat(properties.getCacheTtlSeconds()).isEqualTo(30);
    }

    @Test
    void shouldDefaultRagCacheProperties() {
        RagProperties properties = new RagProperties();

        assertThat(properties.isCacheEnabled()).isTrue();
        assertThat(properties.getCacheMaxSize()).isEqualTo(256);
        assertThat(properties.getCacheTtlSeconds()).isEqualTo(300);
    }
}

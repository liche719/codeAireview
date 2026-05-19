package com.codepilot.infrastructure.embedding;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.flyway.FlywayConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class EmbeddingFlywayConfiguration {

    private final EmbeddingDimensionResolver embeddingDimensionResolver;

    @Bean
    public FlywayConfigurationCustomizer embeddingDimensionFlywayConfigurationCustomizer() {
        return configuration -> {
            int dimension = embeddingDimensionResolver.resolveDimension();
            configuration.placeholders(Map.of("embeddingDimension", String.valueOf(dimension)));
            log.info("Flyway embedding dimension placeholder configured, dimension={}", dimension);
        };
    }
}

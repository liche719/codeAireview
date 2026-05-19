package com.codepilot.infrastructure.embedding;

import dev.langchain4j.model.embedding.DisabledEmbeddingModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.Locale;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class LangChain4jEmbeddingConfig {

    private static final String OPENAI_COMPATIBLE_PROVIDER = "openai-compatible";

    private final EmbeddingProperties embeddingProperties;

    @Bean
    public EmbeddingModel codePilotEmbeddingModel() {
        if (!embeddingProperties.isEnabled()) {
            log.info("LangChain4j EmbeddingModel is disabled by codepilot.embedding.enabled=false");
            return new DisabledEmbeddingModel();
        }
        if (!StringUtils.hasText(embeddingProperties.getApiKey())) {
            log.info("LangChain4j EmbeddingModel uses disabled model because codepilot.embedding.api-key is empty");
            return new DisabledEmbeddingModel();
        }
        if (!isOpenAiCompatibleProvider()) {
            log.warn("LangChain4j EmbeddingModel uses disabled model because provider is unsupported: {}",
                    embeddingProperties.getProvider());
            return new DisabledEmbeddingModel();
        }

        Duration timeout = Duration.ofSeconds(Math.max(1, embeddingProperties.getTimeoutSeconds()));
        String baseUrl = normalizeBaseUrl(embeddingProperties.getBaseUrl());
        Integer dimension = embeddingProperties.getDimension() > 0 ? embeddingProperties.getDimension() : null;

        log.info("Creating LangChain4j EmbeddingModel, provider={}, model={}, baseUrl={}, dimension={}, timeoutSeconds={}",
                embeddingProperties.getProvider(),
                embeddingProperties.getModel(),
                baseUrl,
                dimension == null ? "auto" : dimension,
                timeout.toSeconds());

        var builder = OpenAiEmbeddingModel.builder()
                .baseUrl(baseUrl)
                .apiKey(embeddingProperties.getApiKey())
                .modelName(embeddingProperties.getModel())
                .timeout(timeout);
        if (dimension != null) {
            builder.dimensions(dimension);
        }
        return builder.build();
    }

    private boolean isOpenAiCompatibleProvider() {
        return OPENAI_COMPATIBLE_PROVIDER.equalsIgnoreCase(
                StringUtils.trimWhitespace(embeddingProperties.getProvider() == null
                        ? ""
                        : embeddingProperties.getProvider())
        );
    }

    private String normalizeBaseUrl(String baseUrl) {
        if (!StringUtils.hasText(baseUrl)) {
            return "https://api.openai.com/v1";
        }
        String trimmed = baseUrl.trim();
        if (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed.toLowerCase(Locale.ROOT).startsWith("http")
                ? trimmed
                : "https://" + trimmed;
    }
}

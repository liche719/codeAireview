package com.codepilot.infrastructure.embedding;

import com.codepilot.common.exception.BusinessException;
import com.codepilot.common.util.SensitiveDataSanitizer;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Slf4j
@Component
@RequiredArgsConstructor
public class EmbeddingDimensionResolver {

    static final int DEFAULT_EMBEDDING_DIMENSION = 1536;

    private static final String OPENAI_COMPATIBLE_PROVIDER = "openai-compatible";

    private static final String PROBE_TEXT = "CodePilot embedding dimension probe";

    private final EmbeddingProperties embeddingProperties;

    private final EmbeddingModel embeddingModel;

    private volatile Integer resolvedDimension;

    public boolean canProbe() {
        return embeddingProperties.isEnabled()
                && StringUtils.hasText(embeddingProperties.getApiKey())
                && OPENAI_COMPATIBLE_PROVIDER.equalsIgnoreCase(
                StringUtils.trimWhitespace(embeddingProperties.getProvider() == null ? "" : embeddingProperties.getProvider())
        );
    }

    public int resolveDimension() {
        Integer current = resolvedDimension;
        if (current != null) {
            return current;
        }

        synchronized (this) {
            if (resolvedDimension != null) {
                return resolvedDimension;
            }

            int configuredDimension = embeddingProperties.getDimension();
            if (configuredDimension > 0) {
                resolvedDimension = configuredDimension;
                log.info("Embedding dimension resolved from configuration, dimension={}", configuredDimension);
                return configuredDimension;
            }

            int detectedDimension = canProbe() ? detectDimension() : DEFAULT_EMBEDDING_DIMENSION;
            if (!canProbe()) {
                log.warn("Embedding dimension cannot be probed, using fallback dimension={}", detectedDimension);
            }
            resolvedDimension = detectedDimension;
            embeddingProperties.setDimension(detectedDimension);
            return detectedDimension;
        }
    }

    private int detectDimension() {
        try {
            Response<Embedding> response = embeddingModel.embed(PROBE_TEXT);
            Embedding embedding = response == null ? null : response.content();
            if (embedding == null || embedding.dimension() <= 0) {
                throw new BusinessException("embedding model returned empty dimension");
            }
            int detectedDimension = embedding.dimension();
            log.info("Embedding dimension auto-detected for Flyway, dimension={}", detectedDimension);
            return detectedDimension;
        } catch (Exception exception) {
            throw new BusinessException("failed to detect embedding dimension: "
                    + SensitiveDataSanitizer.redact(exception.getMessage()));
        }
    }
}

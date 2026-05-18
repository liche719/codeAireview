package com.codepilot.infrastructure.embedding;

import com.codepilot.common.exception.BusinessException;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmbeddingServiceImpl implements EmbeddingService {

    private static final String OPENAI_COMPATIBLE_PROVIDER = "openai-compatible";

    private final EmbeddingProperties embeddingProperties;

    private final EmbeddingModel embeddingModel;

    @Override
    public List<Float> embed(String text) {
        validateEmbeddingAvailable();
        if (!StringUtils.hasText(text)) {
            throw new BusinessException("embedding text must not be blank");
        }

        long startTime = System.currentTimeMillis();
        Response<Embedding> response = embeddingModel.embed(text);
        Embedding embedding = response == null ? null : response.content();
        if (embedding == null || embedding.dimension() == 0) {
            throw new BusinessException("embedding model returned empty vector");
        }

        List<Float> vector = embedding.vectorAsList();
        if (vector.size() != embeddingProperties.getDimension()) {
            throw new BusinessException("embedding dimension mismatch, expected="
                    + embeddingProperties.getDimension() + ", actual=" + vector.size());
        }
        log.info("Embedding generated, textLength={}, dimension={}, costTimeMs={}",
                text.length(), vector.size(), System.currentTimeMillis() - startTime);
        return vector;
    }

    private void validateEmbeddingAvailable() {
        if (!embeddingProperties.isEnabled()) {
            throw new BusinessException("embedding is disabled");
        }
        if (!StringUtils.hasText(embeddingProperties.getApiKey())) {
            throw new BusinessException("embedding api key is missing");
        }
        if (!OPENAI_COMPATIBLE_PROVIDER.equalsIgnoreCase(
                StringUtils.trimWhitespace(embeddingProperties.getProvider() == null
                        ? ""
                        : embeddingProperties.getProvider()))) {
            throw new BusinessException("unsupported embedding provider: " + embeddingProperties.getProvider());
        }
    }
}

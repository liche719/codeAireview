package com.codepilot.infrastructure.embedding;

import com.codepilot.common.exception.BusinessException;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmbeddingDimensionResolverTest {

    @Test
    void shouldUseConfiguredDimensionWithoutProbing() {
        TestContext context = new TestContext(true, "token", 3072);

        int dimension = context.resolver.resolveDimension();

        assertThat(dimension).isEqualTo(3072);
        assertThat(context.embeddingProperties.getDimension()).isEqualTo(3072);
        verifyNoInteractions(context.embeddingModel);
    }

    @Test
    void shouldAutoDetectDimensionAndCacheIt() {
        TestContext context = new TestContext(true, "token", 0);
        when(context.embeddingModel.embed(anyString())).thenReturn(Response.from(Embedding.from(new float[]{0.1F, 0.2F, 0.3F})));

        int dimension = context.resolver.resolveDimension();
        int cachedDimension = context.resolver.resolveDimension();

        assertThat(dimension).isEqualTo(3);
        assertThat(cachedDimension).isEqualTo(3);
        assertThat(context.embeddingProperties.getDimension()).isEqualTo(3);
        verify(context.embeddingModel).embed(anyString());
    }

    @Test
    void shouldFallbackToDefaultWhenProbeUnavailable() {
        TestContext context = new TestContext(false, "", 0);

        int dimension = context.resolver.resolveDimension();

        assertThat(dimension).isEqualTo(EmbeddingDimensionResolver.DEFAULT_EMBEDDING_DIMENSION);
        assertThat(context.embeddingProperties.getDimension()).isEqualTo(EmbeddingDimensionResolver.DEFAULT_EMBEDDING_DIMENSION);
        verifyNoInteractions(context.embeddingModel);
    }

    @Test
    void shouldRedactSecretFromProbeFailureMessage() {
        TestContext context = new TestContext(true, "token", 0);
        when(context.embeddingModel.embed(anyString()))
                .thenThrow(new IllegalStateException("embedding failed token=sk-proj-12345678901234567890"));

        assertThatThrownBy(context.resolver::resolveDimension)
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("failed to detect embedding dimension")
                .hasMessageContaining("[REDACTED]")
                .hasMessageNotContaining("sk-proj-12345678901234567890");
    }

    private static class TestContext {

        private final EmbeddingProperties embeddingProperties = new EmbeddingProperties();

        private final EmbeddingModel embeddingModel = mock(EmbeddingModel.class);

        private final EmbeddingDimensionResolver resolver;

        private TestContext(boolean enabled, String apiKey, int dimension) {
            embeddingProperties.setEnabled(enabled);
            embeddingProperties.setProvider("openai-compatible");
            embeddingProperties.setApiKey(apiKey);
            embeddingProperties.setDimension(dimension);
            resolver = new EmbeddingDimensionResolver(embeddingProperties, embeddingModel);
        }
    }
}

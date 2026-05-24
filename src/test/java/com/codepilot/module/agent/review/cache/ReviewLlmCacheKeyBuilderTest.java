package com.codepilot.module.agent.review.cache;

import com.codepilot.infrastructure.llm.LlmProperties;
import com.codepilot.module.agent.review.ReviewLlmInput;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReviewLlmCacheKeyBuilderTest {

    @Test
    void shouldChangeCacheKeyWhenModelOrPromptSignatureChanges() {
        LlmProperties properties = new LlmProperties();
        properties.setProvider("openai-compatible");
        properties.setModel("gpt-4o-mini");
        ReviewPromptSignatureProvider signatureProvider = mock(ReviewPromptSignatureProvider.class);
        when(signatureProvider.signature()).thenReturn("prompt-v1", "prompt-v2");
        ReviewLlmCacheKeyBuilder builder = new ReviewLlmCacheKeyBuilder(properties, signatureProvider);
        ReviewLlmInput input = new ReviewLlmInput(
                "src/main/java/Demo.java",
                "+code",
                "rules",
                "changed files",
                false,
                false
        );

        ReviewLlmCacheKey first = builder.build("openai-compatible", input);
        ReviewLlmCacheKey second = builder.build("openai-compatible", input);
        properties.setModel("gpt-4.1-mini");
        ReviewLlmCacheKey third = builder.build("openai-compatible", input);

        assertThat(first.value()).isNotEqualTo(second.value());
        assertThat(second.value()).isNotEqualTo(third.value());
        assertThat(first.promptSignature()).isEqualTo("prompt-v1");
        assertThat(second.promptSignature()).isEqualTo("prompt-v2");
    }

    @Test
    void shouldUseLimitedPromptInputInCacheKey() {
        LlmProperties properties = new LlmProperties();
        ReviewPromptSignatureProvider signatureProvider = mock(ReviewPromptSignatureProvider.class);
        when(signatureProvider.signature()).thenReturn("prompt-v1");
        ReviewLlmCacheKeyBuilder builder = new ReviewLlmCacheKeyBuilder(properties, signatureProvider);

        ReviewLlmCacheKey first = builder.build("test", new ReviewLlmInput(
                "src/main/java/Demo.java",
                "+code",
                "rules",
                "changed files",
                false,
                false
        ));
        ReviewLlmCacheKey second = builder.build("test", new ReviewLlmInput(
                "src/main/java/Demo.java",
                "+code",
                "rules changed",
                "changed files",
                false,
                false
        ));

        assertThat(first.value()).isNotEqualTo(second.value());
    }
}

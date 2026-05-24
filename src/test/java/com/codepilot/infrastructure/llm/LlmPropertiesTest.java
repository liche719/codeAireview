package com.codepilot.infrastructure.llm;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LlmPropertiesTest {

    @Test
    void shouldBindReviewInputBudgets() {
        MapConfigurationPropertySource source = new MapConfigurationPropertySource(Map.of(
                "codepilot.llm.max-review-patch-chars", "100",
                "codepilot.llm.max-review-rules-chars", "200",
                "codepilot.llm.max-review-context-chars", "300"
        ));

        LlmProperties properties = new Binder(source)
                .bind("codepilot.llm", Bindable.of(LlmProperties.class))
                .get();

        assertThat(properties.getMaxReviewPatchChars()).isEqualTo(100);
        assertThat(properties.getMaxReviewRulesChars()).isEqualTo(200);
        assertThat(properties.getMaxReviewContextChars()).isEqualTo(300);
    }

    @Test
    void shouldDefaultReviewInputBudgets() {
        LlmProperties properties = new LlmProperties();

        assertThat(properties.getMaxReviewPatchChars()).isEqualTo(12000);
        assertThat(properties.getMaxReviewRulesChars()).isEqualTo(4000);
        assertThat(properties.getMaxReviewContextChars()).isEqualTo(8000);
    }
}

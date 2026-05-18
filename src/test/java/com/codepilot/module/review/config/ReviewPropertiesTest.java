package com.codepilot.module.review.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewPropertiesTest {

    @Test
    void shouldBindReviewLimitProperties() {
        MapConfigurationPropertySource source = new MapConfigurationPropertySource(Map.of(
                "codepilot.review.max-files-per-task", "10",
                "codepilot.review.max-patch-chars-per-file", "5000",
                "codepilot.review.max-total-patch-chars", "30000"
        ));

        ReviewProperties properties = new Binder(source)
                .bind("codepilot.review", Bindable.of(ReviewProperties.class))
                .get();

        assertThat(properties.getMaxFilesPerTask()).isEqualTo(10);
        assertThat(properties.getMaxPatchCharsPerFile()).isEqualTo(5000);
        assertThat(properties.getMaxTotalPatchChars()).isEqualTo(30000);
    }
}

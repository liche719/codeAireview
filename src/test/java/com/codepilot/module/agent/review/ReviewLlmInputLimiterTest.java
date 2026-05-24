package com.codepilot.module.agent.review;

import com.codepilot.infrastructure.llm.LlmProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewLlmInputLimiterTest {

    @Test
    void shouldRejectPatchOverBudget() {
        LlmProperties properties = new LlmProperties();
        properties.setMaxReviewPatchChars(5);
        ReviewLlmInputLimiter limiter = new ReviewLlmInputLimiter(properties);

        assertThat(limiter.isPatchWithinBudget("123456")).isFalse();
        assertThat(limiter.isPatchWithinBudget("12345")).isTrue();
    }

    @Test
    void shouldTreatNonPositivePatchBudgetAsUnlimited() {
        LlmProperties properties = new LlmProperties();
        properties.setMaxReviewPatchChars(0);
        ReviewLlmInputLimiter limiter = new ReviewLlmInputLimiter(properties);

        assertThat(limiter.isPatchWithinBudget("123456")).isTrue();
    }

    @Test
    void shouldTruncateRulesAndChangedFilesContext() {
        LlmProperties properties = new LlmProperties();
        properties.setMaxReviewRulesChars(20);
        properties.setMaxReviewContextChars(25);
        ReviewLlmInputLimiter limiter = new ReviewLlmInputLimiter(properties);

        ReviewLlmInput input = limiter.limit(
                "src/Demo.java",
                "+code",
                "rules-context-that-is-too-long",
                "changed-files-context-that-is-too-long"
        );

        assertThat(input.rulesContext()).hasSize(20);
        assertThat(input.changedFilesContext()).hasSize(25);
        assertThat(input.rulesContext()).contains("[TRUNCATED");
        assertThat(input.changedFilesContext()).contains("[TRUNCATED");
        assertThat(input.truncatedRulesContext()).isTrue();
        assertThat(input.truncatedChangedFilesContext()).isTrue();
    }
}

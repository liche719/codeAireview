package com.codepilot.module.review.validation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewBotValidationFixtureTest {

    @Test
    void shouldKeepAnObviousSqlRiskSnippetForReviewBotValidation() {
        String unsafeSql = "SELECT * FROM users WHERE id = ${id}";

        assertThat(unsafeSql).contains("${id}");
    }
}

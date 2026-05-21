package com.codepilot.module.command.service.impl;

import com.codepilot.module.command.entity.PrCommandTask;
import com.codepilot.module.review.entity.ReviewTask;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PrCommandTaskServiceImplTest {

    @Test
    void shouldRequireSameHeadShaBeforeReusingReviewTaskForFix() {
        PrCommandTaskServiceImpl service = new PrCommandTaskServiceImpl(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
        PrCommandTask commandTask = new PrCommandTask();
        commandTask.setHeadSha("abc123");
        ReviewTask reviewTask = new ReviewTask();
        reviewTask.setHeadSha("ABC123");

        assertThat(service.hasSameHeadSha(commandTask, reviewTask)).isTrue();

        reviewTask.setHeadSha("def456");
        assertThat(service.hasSameHeadSha(commandTask, reviewTask)).isFalse();

        reviewTask.setHeadSha(null);
        assertThat(service.hasSameHeadSha(commandTask, reviewTask)).isFalse();
    }
}

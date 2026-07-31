package com.codepilot.module.review.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewMetricsTest {

    @Test
    void shouldExposeStableLowCardinalityReviewMetrics() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ReviewMetrics metrics = new ReviewMetrics(registry);

        metrics.recordTaskCreation(true);
        metrics.recordTaskCreation(false);
        metrics.recordQueueWait(LocalDateTime.now().minusSeconds(1));
        metrics.recordDuplicateDelivery();
        metrics.recordTaskExecution(System.nanoTime(), "success");
        metrics.recordModelFileReview(true);
        metrics.recordModelFileReview(false);

        assertThat(registry.get("codepilot.review.task.creation").tag("result", "created").counter().count())
                .isEqualTo(1D);
        assertThat(registry.get("codepilot.review.task.creation").tag("result", "reused").counter().count())
                .isEqualTo(1D);
        assertThat(registry.get("codepilot.review.task.queue.wait").timer().count()).isEqualTo(1L);
        assertThat(registry.get("codepilot.review.task.execution").tag("result", "duplicate_skipped").counter().count())
                .isEqualTo(1D);
        assertThat(registry.get("codepilot.review.task.duration").tag("result", "success").timer().count())
                .isEqualTo(1L);
        assertThat(registry.get("codepilot.review.model.file").tag("result", "failure").counter().count())
                .isEqualTo(1D);
    }
}

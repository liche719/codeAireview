package com.codepilot.module.review.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
public class ReviewMetrics {

    private final MeterRegistry meterRegistry;

    public void recordTaskCreation(boolean created) {
        meterRegistry.counter("codepilot.review.task.creation", "result", created ? "created" : "reused")
                .increment();
    }

    public void recordQueueWait(LocalDateTime createdAt) {
        if (createdAt == null) {
            return;
        }
        Duration wait = Duration.between(createdAt, LocalDateTime.now());
        if (!wait.isNegative()) {
            Timer.builder("codepilot.review.task.queue.wait")
                    .description("Time from task creation until a consumer claims it")
                    .register(meterRegistry)
                    .record(wait);
        }
    }

    public void recordDuplicateDelivery() {
        meterRegistry.counter("codepilot.review.task.execution", "result", "duplicate_skipped").increment();
    }

    public void recordTaskExecution(long startNanos, String result) {
        Timer.builder("codepilot.review.task.duration")
                .description("Review task execution duration")
                .tag("result", result)
                .register(meterRegistry)
                .record(Math.max(0L, System.nanoTime() - startNanos), TimeUnit.NANOSECONDS);
        meterRegistry.counter("codepilot.review.task.execution", "result", result).increment();
    }

    public void recordModelFileReview(boolean success) {
        meterRegistry.counter("codepilot.review.model.file", "result", success ? "success" : "failure")
                .increment();
    }
}

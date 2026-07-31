package com.codepilot.module.review.publisher;

import com.codepilot.common.util.SensitiveDataSanitizer;
import com.codepilot.module.review.config.ReviewProperties;
import com.codepilot.module.review.entity.ReviewTask;
import com.codepilot.module.review.mapper.ReviewTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReviewTaskPublicationService {

    private final ReviewTaskMapper reviewTaskMapper;

    private final ReviewCommentPublisher reviewCommentPublisher;

    private final ReviewProperties reviewProperties;

    private final MeterRegistry meterRegistry;

    public void publish(Long taskId) {
        LocalDateTime now = LocalDateTime.now();
        if (reviewTaskMapper.claimPublication(
                taskId,
                now,
                now.minus(reviewProperties.getOutboxLockTimeout())
        ) == 0) {
            return;
        }

        ReviewTask task = reviewTaskMapper.selectById(taskId);
        if (task == null) {
            return;
        }

        ReviewCommentPublishResult result = reviewCommentPublisher.publish(task);
        if (result.published()) {
            reviewTaskMapper.finishPublication(taskId, "PUBLISHED", null, null, LocalDateTime.now());
            meterRegistry.counter("codepilot.review.github.publish", "result", "success").increment();
            return;
        }
        if (result.skipped()) {
            reviewTaskMapper.finishPublication(taskId, "SKIPPED", null, null, LocalDateTime.now());
            meterRegistry.counter("codepilot.review.github.publish", "result", "skipped").increment();
            return;
        }

        String error = SensitiveDataSanitizer.redactAndTruncate(result.errorMessage(), 2000);
        int attemptCount = task.getPublishAttemptCount() == null ? 1 : task.getPublishAttemptCount();
        if (attemptCount >= Math.max(1, reviewProperties.getPublicationMaxAttempts())) {
            reviewTaskMapper.finishPublication(taskId, "FAILED_FINAL", error, null, LocalDateTime.now());
            meterRegistry.counter("codepilot.review.github.publish", "result", "failed").increment();
            log.error("GitHub review publication exhausted retries, taskId={}, attempt={}, message={}",
                    taskId, attemptCount, error);
            return;
        }
        Duration delay = retryDelay(task.getPublishAttemptCount());
        LocalDateTime retryAt = LocalDateTime.now().plus(delay);
        reviewTaskMapper.finishPublication(taskId, "FAILED", error, retryAt, LocalDateTime.now());
        meterRegistry.counter("codepilot.review.github.publish", "result", "retry").increment();
        log.warn("GitHub review publication scheduled for retry, taskId={}, retryAt={}, message={}",
                taskId, retryAt, error);
    }

    private Duration retryDelay(Integer attemptCount) {
        int attempt = attemptCount == null ? 1 : Math.max(1, attemptCount);
        long initialMillis = Math.max(1000L, reviewProperties.getOutboxRetryInitialDelay().toMillis());
        long maxMillis = Math.max(initialMillis, reviewProperties.getOutboxRetryMaxDelay().toMillis());
        int exponent = Math.min(attempt - 1, 20);
        long multiplier = 1L << exponent;
        long delay = initialMillis > Long.MAX_VALUE / multiplier ? maxMillis : initialMillis * multiplier;
        return Duration.ofMillis(Math.min(delay, maxMillis));
    }
}

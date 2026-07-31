package com.codepilot.module.review.queue;

import com.codepilot.common.util.SensitiveDataSanitizer;
import com.codepilot.module.review.config.ReviewProperties;
import com.codepilot.module.review.entity.ReviewTaskOutbox;
import com.codepilot.module.review.mapper.ReviewTaskOutboxMapper;
import com.codepilot.task.ReviewTaskProducer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
@ConditionalOnProperty(name = "codepilot.scheduling.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class ReviewTaskOutboxPublisher {

    private final ReviewTaskOutboxMapper reviewTaskOutboxMapper;

    private final ReviewTaskProducer reviewTaskProducer;

    private final ReviewProperties reviewProperties;

    private final MeterRegistry meterRegistry;

    @Scheduled(fixedDelayString = "${codepilot.review.outbox-poll-interval-millis:1000}")
    public void publishDueMessages() {
        LocalDateTime now = LocalDateTime.now();
        List<ReviewTaskOutbox> messages = reviewTaskOutboxMapper.claimDue(
                now,
                now.minus(reviewProperties.getOutboxLockTimeout()),
                Math.max(1, reviewProperties.getOutboxBatchSize())
        );
        for (ReviewTaskOutbox message : messages) {
            publish(message);
        }
    }

    private void publish(ReviewTaskOutbox message) {
        LocalDateTime now = LocalDateTime.now();
        try {
            reviewTaskProducer.sendAndConfirm(message.getTaskId(), reviewProperties.getOutboxConfirmTimeout());
            reviewTaskOutboxMapper.markPublished(message.getId(), now);
            meterRegistry.counter("codepilot.review.outbox.publish", "result", "success").increment();
        } catch (Exception exception) {
            int nextAttempt = safeAttemptCount(message) + 1;
            String error = SensitiveDataSanitizer.redactAndTruncate(exception.getMessage(), 2000);
            if (nextAttempt >= Math.max(1, reviewProperties.getOutboxMaxAttempts())) {
                reviewTaskOutboxMapper.markFailed(message.getId(), error, now);
                meterRegistry.counter("codepilot.review.outbox.publish", "result", "failed").increment();
                log.error("Review task outbox publish exhausted retries, outboxId={}, taskId={}, attempt={}, errorType={}, message={}",
                        message.getId(), message.getTaskId(), nextAttempt,
                        exception.getClass().getSimpleName(), error);
                return;
            }
            Duration delay = retryDelay(nextAttempt);
            reviewTaskOutboxMapper.markRetry(message.getId(), now.plus(delay), error, now);
            meterRegistry.counter("codepilot.review.outbox.publish", "result", "retry").increment();
            log.warn("Review task outbox publish failed, outboxId={}, taskId={}, attempt={}, retryDelayMillis={}, errorType={}, message={}",
                    message.getId(), message.getTaskId(), nextAttempt, delay.toMillis(),
                    exception.getClass().getSimpleName(), error);
        }
    }

    private int safeAttemptCount(ReviewTaskOutbox message) {
        return message.getAttemptCount() == null ? 0 : Math.max(0, message.getAttemptCount());
    }

    private Duration retryDelay(int attempt) {
        long initialMillis = Math.max(1L, reviewProperties.getOutboxRetryInitialDelay().toMillis());
        long maxMillis = Math.max(initialMillis, reviewProperties.getOutboxRetryMaxDelay().toMillis());
        int exponent = Math.min(Math.max(0, attempt - 1), 20);
        long multiplier = 1L << exponent;
        long delay = initialMillis > Long.MAX_VALUE / multiplier
                ? maxMillis
                : initialMillis * multiplier;
        return Duration.ofMillis(Math.min(delay, maxMillis));
    }
}

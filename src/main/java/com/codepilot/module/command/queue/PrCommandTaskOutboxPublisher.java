package com.codepilot.module.command.queue;

import com.codepilot.common.util.SensitiveDataSanitizer;
import com.codepilot.module.command.config.PrCommandTaskProperties;
import com.codepilot.module.command.entity.PrCommandTaskOutbox;
import com.codepilot.module.command.mapper.PrCommandTaskOutboxMapper;
import com.codepilot.task.PrCommandTaskProducer;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
public class PrCommandTaskOutboxPublisher {

    private final PrCommandTaskOutboxMapper outboxMapper;

    private final PrCommandTaskProducer producer;

    private final PrCommandTaskProperties properties;

    private final MeterRegistry meterRegistry;

    @Scheduled(fixedDelayString = "${codepilot.command.outbox-poll-interval-millis:1000}")
    public void publishDueMessages() {
        LocalDateTime now = LocalDateTime.now();
        List<PrCommandTaskOutbox> messages = outboxMapper.claimDue(
                now, now.minus(properties.getOutboxLockTimeout()), Math.max(1, properties.getOutboxBatchSize()));
        for (PrCommandTaskOutbox message : messages) {
            publish(message);
        }
    }

    private void publish(PrCommandTaskOutbox message) {
        LocalDateTime now = LocalDateTime.now();
        try {
            producer.sendAndConfirm(message.getCommandTaskId(), properties.getOutboxConfirmTimeout());
            outboxMapper.markPublished(message.getId(), now);
            meterRegistry.counter("codepilot.command.outbox.publish", "result", "success").increment();
        } catch (Exception exception) {
            int nextAttempt = safeAttemptCount(message) + 1;
            String error = SensitiveDataSanitizer.redactAndTruncate(exception.getMessage(), 2000);
            if (nextAttempt >= Math.max(1, properties.getOutboxMaxAttempts())) {
                outboxMapper.markFailed(message.getId(), error, now);
                meterRegistry.counter("codepilot.command.outbox.publish", "result", "failed").increment();
                log.error("PR command outbox publish exhausted retries, outboxId={}, commandTaskId={}, attempt={}, message={}",
                        message.getId(), message.getCommandTaskId(), nextAttempt, error);
                return;
            }
            Duration delay = retryDelay(nextAttempt);
            outboxMapper.markRetry(message.getId(), now.plus(delay), error, now);
            meterRegistry.counter("codepilot.command.outbox.publish", "result", "retry").increment();
            log.warn("PR command outbox publish failed, outboxId={}, commandTaskId={}, attempt={}, retryDelayMillis={}, message={}",
                    message.getId(), message.getCommandTaskId(), nextAttempt, delay.toMillis(), error);
        }
    }

    private int safeAttemptCount(PrCommandTaskOutbox message) {
        return message.getAttemptCount() == null ? 0 : Math.max(0, message.getAttemptCount());
    }

    private Duration retryDelay(int attempt) {
        long initialMillis = Math.max(1L, properties.getOutboxRetryInitialDelay().toMillis());
        long maxMillis = Math.max(initialMillis, properties.getOutboxRetryMaxDelay().toMillis());
        int exponent = Math.min(Math.max(0, attempt - 1), 20);
        long multiplier = 1L << exponent;
        long delay = initialMillis > Long.MAX_VALUE / multiplier ? maxMillis : initialMillis * multiplier;
        return Duration.ofMillis(Math.min(delay, maxMillis));
    }
}

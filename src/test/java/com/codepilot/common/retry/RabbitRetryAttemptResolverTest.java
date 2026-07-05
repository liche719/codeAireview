package com.codepilot.common.retry;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.retry.context.RetryContextSupport;
import org.springframework.retry.support.RetrySynchronizationManager;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class RabbitRetryAttemptResolverTest {

    private final RabbitRetryAttemptResolver resolver = new RabbitRetryAttemptResolver();

    @AfterEach
    void clearRetryContext() {
        RetrySynchronizationManager.clear();
    }

    @Test
    void shouldResolveFirstAttemptWhenRetryContextIsMissing() {
        ReflectionTestUtils.setField(resolver, "rabbitRetryMaxAttempts", 3);

        RetryAttempt attempt = resolver.currentAttempt();

        assertThat(attempt.attempt()).isEqualTo(1);
        assertThat(attempt.maxAttempts()).isEqualTo(1);
        assertThat(attempt.finalAttempt()).isTrue();
        assertThat(attempt.detail("IllegalStateException"))
                .isEqualTo("attempt=1/1, errorType=IllegalStateException");
    }

    @Test
    void shouldResolveFinalAttemptFromRetryContext() {
        ReflectionTestUtils.setField(resolver, "rabbitRetryMaxAttempts", 3);
        registerRetryContext(2);

        RetryAttempt attempt = resolver.currentAttempt();

        assertThat(attempt.attempt()).isEqualTo(3);
        assertThat(attempt.maxAttempts()).isEqualTo(3);
        assertThat(attempt.finalAttempt()).isTrue();
    }

    private void registerRetryContext(int retryCount) {
        RetryContextSupport context = new RetryContextSupport(null);
        for (int i = 0; i < retryCount; i++) {
            context.registerThrowable(new RuntimeException("retry-" + i));
        }
        RetrySynchronizationManager.register(context);
    }
}

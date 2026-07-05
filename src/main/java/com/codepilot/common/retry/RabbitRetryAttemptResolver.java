package com.codepilot.common.retry;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.retry.RetryContext;
import org.springframework.retry.support.RetrySynchronizationManager;
import org.springframework.stereotype.Component;

@Component
public class RabbitRetryAttemptResolver {

    @Value("${spring.rabbitmq.listener.simple.retry.max-attempts:3}")
    private int rabbitRetryMaxAttempts = 3;

    public RetryAttempt currentAttempt() {
        RetryContext retryContext = RetrySynchronizationManager.getContext();
        if (retryContext == null) {
            return new RetryAttempt(1, 1, true);
        }
        int maxAttempts = Math.max(1, rabbitRetryMaxAttempts);
        int retryCount = retryContext.getRetryCount();
        int attempt = retryCount + 1;
        return new RetryAttempt(attempt, maxAttempts, retryCount >= maxAttempts - 1);
    }
}

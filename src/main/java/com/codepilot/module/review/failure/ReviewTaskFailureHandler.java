package com.codepilot.module.review.failure;

import com.codepilot.common.util.SensitiveDataSanitizer;
import com.codepilot.module.review.entity.ReviewTask;
import com.codepilot.module.review.state.ReviewTaskStateManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.retry.RetryContext;
import org.springframework.retry.support.RetrySynchronizationManager;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReviewTaskFailureHandler {

    private final ReviewTaskStateManager reviewTaskStateManager;

    @Value("${spring.rabbitmq.listener.simple.retry.max-attempts:3}")
    private int rabbitRetryMaxAttempts = 3;

    public ReviewTaskFailureResult handle(ReviewTask task, Exception exception) {
        String errorMessage = sanitizedErrorMessage(exception);
        String retryDetail = retryDetail(exception);
        boolean finalAttempt = isFinalRetryAttempt();
        if (finalAttempt) {
            reviewTaskStateManager.markFailed(task, errorMessage);
            log.error("Review task failed, taskId={}, {}, message={}",
                    task.getId(), retryDetail, errorMessage);
        } else {
            reviewTaskStateManager.markRetrying(task, errorMessage);
            log.warn("Review task failed and will be retried, taskId={}, {}, message={}",
                    task.getId(), retryDetail, errorMessage);
        }
        return new ReviewTaskFailureResult(errorType(exception), errorMessage, finalAttempt);
    }

    private boolean isFinalRetryAttempt() {
        RetryContext retryContext = RetrySynchronizationManager.getContext();
        if (retryContext == null) {
            return true;
        }
        int maxAttempts = Math.max(1, rabbitRetryMaxAttempts);
        return retryContext.getRetryCount() >= maxAttempts - 1;
    }

    private String retryDetail(Exception exception) {
        RetryContext retryContext = RetrySynchronizationManager.getContext();
        String attempt = retryContext == null
                ? "attempt=1/1"
                : "attempt=" + (retryContext.getRetryCount() + 1) + "/" + Math.max(1, rabbitRetryMaxAttempts);
        return attempt + ", errorType=" + errorType(exception);
    }

    private String sanitizedErrorMessage(Exception exception) {
        String message = exception == null ? null : exception.getMessage();
        String sanitized = SensitiveDataSanitizer.redactAndTruncate(message, 2000);
        return StringUtils.hasText(sanitized) ? sanitized : errorType(exception);
    }

    private String errorType(Exception exception) {
        return exception == null ? "unknown error" : exception.getClass().getSimpleName();
    }
}

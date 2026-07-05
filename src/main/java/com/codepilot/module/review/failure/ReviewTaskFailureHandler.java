package com.codepilot.module.review.failure;

import com.codepilot.common.util.SensitiveDataSanitizer;
import com.codepilot.common.retry.RabbitRetryAttemptResolver;
import com.codepilot.common.retry.RetryAttempt;
import com.codepilot.module.review.entity.ReviewTask;
import com.codepilot.module.review.state.ReviewTaskStateManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReviewTaskFailureHandler {

    private final ReviewTaskStateManager reviewTaskStateManager;

    private final RabbitRetryAttemptResolver retryAttemptResolver;

    public ReviewTaskFailureResult handle(ReviewTask task, Exception exception) {
        String errorMessage = sanitizedErrorMessage(exception);
        RetryAttempt retryAttempt = retryAttemptResolver.currentAttempt();
        String retryDetail = retryAttempt.detail(errorType(exception));
        if (retryAttempt.finalAttempt()) {
            reviewTaskStateManager.markFailed(task, errorMessage);
            log.error("Review task failed, taskId={}, {}, message={}",
                    task.getId(), retryDetail, errorMessage);
        } else {
            reviewTaskStateManager.markRetrying(task, errorMessage);
            log.warn("Review task failed and will be retried, taskId={}, {}, message={}",
                    task.getId(), retryDetail, errorMessage);
        }
        return new ReviewTaskFailureResult(errorType(exception), errorMessage, retryAttempt.finalAttempt());
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

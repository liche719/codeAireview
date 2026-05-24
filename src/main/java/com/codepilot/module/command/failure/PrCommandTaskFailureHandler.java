package com.codepilot.module.command.failure;

import com.codepilot.common.util.SensitiveDataSanitizer;
import com.codepilot.module.command.entity.PrCommandTask;
import com.codepilot.module.command.fix.FixResultCommenter;
import com.codepilot.module.command.service.PrCommandTaskLogService;
import com.codepilot.module.command.state.PrCommandTaskStateManager;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.retry.RetryContext;
import org.springframework.retry.support.RetrySynchronizationManager;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@RequiredArgsConstructor
public class PrCommandTaskFailureHandler {

    private final PrCommandTaskStateManager commandTaskStateManager;

    private final PrCommandTaskLogService commandTaskLogService;

    private final FixResultCommenter fixResultCommenter;

    @Value("${spring.rabbitmq.listener.simple.retry.max-attempts:3}")
    private int rabbitRetryMaxAttempts = 3;

    public void handleNonRetryable(PrCommandTask task, Exception exception) {
        String message = sanitizedErrorMessage(exception);
        commandTaskStateManager.markFailed(task, message);
        commandTaskLogService.record(task.getId(), "FAILED", false, message, null);
        fixResultCommenter.fixFailed(task, message);
    }

    public void handleRetryable(PrCommandTask task, Exception exception) {
        String message = sanitizedErrorMessage(exception);
        if (isFinalRetryAttempt()) {
            commandTaskStateManager.markFailed(task, message);
            commandTaskLogService.record(task.getId(), "FAILED", false, message, retryDetail(exception));
            fixResultCommenter.fixFailed(task, message);
        } else {
            commandTaskStateManager.markRetrying(task, message);
            commandTaskLogService.record(task.getId(), "RETRYING", false, message, retryDetail(exception));
        }
        throw new IllegalStateException("PR fix command task failed, commandTaskId=" + task.getId(), exception);
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

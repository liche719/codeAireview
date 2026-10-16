package com.codepilot.module.command.failure;

import com.codepilot.common.util.SensitiveDataSanitizer;
import com.codepilot.common.retry.RabbitRetryAttemptResolver;
import com.codepilot.common.retry.RetryAttempt;
import com.codepilot.module.command.entity.PrCommandTask;
import com.codepilot.module.command.fix.FixResultCommenter;
import com.codepilot.module.command.service.PrCommandTaskLogService;
import com.codepilot.module.command.state.PrCommandTaskStateManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@RequiredArgsConstructor
public class PrCommandTaskFailureHandler {

    private final PrCommandTaskStateManager commandTaskStateManager;

    private final PrCommandTaskLogService commandTaskLogService;

    private final FixResultCommenter fixResultCommenter;

    private final RabbitRetryAttemptResolver retryAttemptResolver;

    public void handleNonRetryable(PrCommandTask task, Exception exception) {
        String message = sanitizedErrorMessage(exception);
        commandTaskStateManager.markFailed(task, message);
        commandTaskLogService.record(task.getId(), "FAILED", false, message, null);
        fixResultCommenter.fixFailed(task, message);
    }

    public void handleRetryable(PrCommandTask task, Exception exception) {
        String message = sanitizedErrorMessage(exception);
        RetryAttempt retryAttempt = retryAttemptResolver.currentAttempt();
        String retryDetail = retryAttempt.detail(errorType(exception));
        if (retryAttempt.finalAttempt()) {
            commandTaskStateManager.markFailed(task, message);
            commandTaskLogService.record(task.getId(), "FAILED", false, message, retryDetail);
            fixResultCommenter.fixFailed(task, message);
        } else {
            commandTaskStateManager.markRetrying(task, message);
            commandTaskLogService.record(task.getId(), "RETRYING", false, message, retryDetail);
        }
        throw new IllegalStateException("PR fix command task failed, commandTaskId=" + task.getId(), exception);
    }

    public void handleAgentRetryable(PrCommandTask task, Exception exception, Runnable finalFailureNotifier) {
        String message = sanitizedErrorMessage(exception);
        RetryAttempt retryAttempt = retryAttemptResolver.currentAttempt();
        String retryDetail = retryAttempt.detail(errorType(exception));
        if (retryAttempt.finalAttempt()) {
            commandTaskStateManager.markFailed(task, message);
            commandTaskLogService.record(task.getId(), "AGENT_FAILED", false, message, retryDetail);
            if (finalFailureNotifier != null) {
                finalFailureNotifier.run();
            }
        } else {
            commandTaskStateManager.markRetrying(task, message);
            commandTaskLogService.record(task.getId(), "AGENT_RETRYING", false, message, retryDetail);
        }
        throw new IllegalStateException("Agent command task failed, commandTaskId=" + task.getId(), exception);
    }

    /**
     * Agent 侧<b>终态</b>失败：不抛异常，消息正常 ack，因此不会重试。
     *
     * <p>用于"重试也不会变好"的失败——目前只有 {@code AgentLimitExceededException} 这类硬上限。
     * 与 {@link #handleAgentRetryable} 的关键区别就是<b>末尾不 throw</b>：
     * 抛出去会被 MQ 重新投递，而这个异常重试还会撞同一堵墙。</p>
     */
    public void handleAgentNonRetryable(PrCommandTask task, Exception exception, Runnable terminalNotifier) {
        String message = sanitizedErrorMessage(exception);
        commandTaskStateManager.markFailed(task, message);
        commandTaskLogService.record(task.getId(), "AGENT_FAILED", false, message, "terminal=true");
        if (terminalNotifier != null) {
            terminalNotifier.run();
        }
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

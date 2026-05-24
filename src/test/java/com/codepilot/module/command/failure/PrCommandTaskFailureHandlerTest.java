package com.codepilot.module.command.failure;

import com.codepilot.module.command.entity.PrCommandTask;
import com.codepilot.module.command.fix.FixResultCommenter;
import com.codepilot.module.command.service.PrCommandTaskLogService;
import com.codepilot.module.command.state.PrCommandTaskStateManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.retry.context.RetryContextSupport;
import org.springframework.retry.support.RetrySynchronizationManager;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class PrCommandTaskFailureHandlerTest {

    private final PrCommandTaskStateManager stateManager = mock(PrCommandTaskStateManager.class);

    private final PrCommandTaskLogService logService = mock(PrCommandTaskLogService.class);

    private final FixResultCommenter commenter = mock(FixResultCommenter.class);

    private final PrCommandTaskFailureHandler failureHandler =
            new PrCommandTaskFailureHandler(stateManager, logService, commenter);

    @AfterEach
    void clearRetryContext() {
        RetrySynchronizationManager.clear();
    }

    @Test
    void shouldMarkNonRetryableFailureWithoutRethrowing() {
        PrCommandTask task = task();

        failureHandler.handleNonRetryable(task, new IllegalArgumentException("bad request"));

        verify(stateManager).markFailed(task, "bad request");
        verify(logService).record(1L, "FAILED", false, "bad request", null);
        verify(commenter).fixFailed(task, "bad request");
    }

    @Test
    void shouldMarkRetryingAndSkipCommentBeforeFinalAttempt() {
        PrCommandTask task = task();
        ReflectionTestUtils.setField(failureHandler, "rabbitRetryMaxAttempts", 3);
        registerRetryContext(0);

        assertThatThrownBy(() -> failureHandler.handleRetryable(task, new IllegalStateException("temporary error")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PR fix command task failed");

        verify(stateManager).markRetrying(task, "temporary error");
        verify(logService).record(eq(1L), eq("RETRYING"), eq(false), eq("temporary error"), contains("attempt=1/3"));
        verify(commenter, never()).fixFailed(eq(task), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void shouldMarkFailedAndCommentOnFinalRetryAttempt() {
        PrCommandTask task = task();
        ReflectionTestUtils.setField(failureHandler, "rabbitRetryMaxAttempts", 3);
        registerRetryContext(2);

        assertThatThrownBy(() -> failureHandler.handleRetryable(task, new IllegalStateException("final error")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PR fix command task failed");

        verify(stateManager).markFailed(task, "final error");
        verify(logService).record(eq(1L), eq("FAILED"), eq(false), eq("final error"), contains("attempt=3/3"));
        verify(commenter).fixFailed(task, "final error");
    }

    @Test
    void shouldRedactSecretsBeforePersistingFailure() {
        PrCommandTask task = task();
        String secret = "ghp_123456789012345678901234567890123456";

        failureHandler.handleNonRetryable(task, new IllegalArgumentException("token=" + secret));

        verify(stateManager).markFailed(eq(task), org.mockito.ArgumentMatchers.argThat(message ->
                message.contains("[REDACTED]") && !message.contains(secret)
        ));
        verify(commenter).fixFailed(eq(task), org.mockito.ArgumentMatchers.argThat(message ->
                message.contains("[REDACTED]") && !message.contains(secret)
        ));
    }

    private void registerRetryContext(int retryCount) {
        RetryContextSupport context = new RetryContextSupport(null);
        for (int i = 0; i < retryCount; i++) {
            context.registerThrowable(new RuntimeException("retry-" + i));
        }
        RetrySynchronizationManager.register(context);
    }

    private PrCommandTask task() {
        PrCommandTask task = new PrCommandTask();
        task.setId(1L);
        return task;
    }
}

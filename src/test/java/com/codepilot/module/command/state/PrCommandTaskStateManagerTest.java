package com.codepilot.module.command.state;

import com.codepilot.module.command.entity.PrCommandTask;
import com.codepilot.module.command.mapper.PrCommandTaskMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class PrCommandTaskStateManagerTest {

    private final PrCommandTaskMapper mapper = mock(PrCommandTaskMapper.class);

    private final PrCommandTaskStateManager stateManager = new PrCommandTaskStateManager(mapper);

    @Test
    void shouldDetectTerminalStatusesCaseInsensitively() {
        assertThat(stateManager.isTerminalStatus("SUCCESS")).isTrue();
        assertThat(stateManager.isTerminalStatus("failed")).isTrue();
        assertThat(stateManager.isTerminalStatus("RUNNING")).isFalse();
        assertThat(stateManager.isTerminalStatus(null)).isFalse();
    }

    @Test
    void shouldMarkSuccessWithCommitSha() {
        PrCommandTask task = task();
        ArgumentCaptor<PrCommandTask> taskCaptor = ArgumentCaptor.forClass(PrCommandTask.class);

        stateManager.markSuccess(task, "abc123");

        verify(mapper).updateById(taskCaptor.capture());
        assertThat(taskCaptor.getValue().getStatus()).isEqualTo("SUCCESS");
        assertThat(taskCaptor.getValue().getCommitSha()).isEqualTo("abc123");
        assertThat(taskCaptor.getValue().getErrorMessage()).isNull();
        assertThat(taskCaptor.getValue().getFinishedAt()).isNotNull();
    }

    @Test
    void shouldMarkRetryingWithoutFinishingTask() {
        PrCommandTask task = task();
        ArgumentCaptor<PrCommandTask> taskCaptor = ArgumentCaptor.forClass(PrCommandTask.class);

        stateManager.markRetrying(task, "temporary error");

        verify(mapper).updateById(taskCaptor.capture());
        assertThat(taskCaptor.getValue().getStatus()).isEqualTo("RUNNING");
        assertThat(taskCaptor.getValue().getErrorMessage()).isEqualTo("temporary error");
        assertThat(taskCaptor.getValue().getFinishedAt()).isNull();
    }

    @Test
    void shouldRedactFailedMessageBeforePersisting() {
        PrCommandTask task = task();
        String secret = "ghp_123456789012345678901234567890123456";
        ArgumentCaptor<PrCommandTask> taskCaptor = ArgumentCaptor.forClass(PrCommandTask.class);

        stateManager.markFailed(task, "validation token=" + secret);

        verify(mapper).updateById(taskCaptor.capture());
        assertThat(taskCaptor.getValue().getStatus()).isEqualTo("FAILED");
        assertThat(taskCaptor.getValue().getErrorMessage())
                .contains("[REDACTED]")
                .doesNotContain(secret);
        assertThat(taskCaptor.getValue().getFinishedAt()).isNotNull();
    }

    @Test
    void shouldStoreOnlyRedactedPatchPreview() {
        PrCommandTask task = task();
        String secret = "ghp_123456789012345678901234567890123456";
        ArgumentCaptor<PrCommandTask> taskCaptor = ArgumentCaptor.forClass(PrCommandTask.class);

        stateManager.storeGeneratedPatchPreview(task, "+String token = \"" + secret + "\";");

        verify(mapper).updateById(taskCaptor.capture());
        assertThat(taskCaptor.getValue().getGeneratedPatch())
                .contains("[REDACTED]")
                .doesNotContain(secret);
        assertThat(taskCaptor.getValue().getUpdatedAt()).isNotNull();
    }

    private PrCommandTask task() {
        PrCommandTask task = new PrCommandTask();
        task.setId(1L);
        return task;
    }
}

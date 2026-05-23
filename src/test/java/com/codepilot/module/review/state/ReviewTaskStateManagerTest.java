package com.codepilot.module.review.state;

import com.codepilot.module.review.entity.ReviewTask;
import com.codepilot.module.review.mapper.ReviewTaskMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ReviewTaskStateManagerTest {

    @Test
    void shouldMarkTaskSuccessWithReviewSummary() {
        ReviewTaskMapper mapper = mock(ReviewTaskMapper.class);
        ReviewTaskStateManager stateManager = new ReviewTaskStateManager(mapper);
        ReviewTask task = task();
        ArgumentCaptor<ReviewTask> taskCaptor = ArgumentCaptor.forClass(ReviewTask.class);

        stateManager.markSuccess(task, 3, 2, "HIGH");

        verify(mapper).updateById(taskCaptor.capture());
        assertThat(taskCaptor.getValue().getStatus()).isEqualTo("SUCCESS");
        assertThat(taskCaptor.getValue().getTotalFiles()).isEqualTo(3);
        assertThat(taskCaptor.getValue().getTotalIssues()).isEqualTo(2);
        assertThat(taskCaptor.getValue().getRiskLevel()).isEqualTo("HIGH");
        assertThat(taskCaptor.getValue().getErrorMessage()).isNull();
        assertThat(taskCaptor.getValue().getFinishedAt()).isNotNull();
    }

    @Test
    void shouldMarkTaskRetryingWithoutFinishingIt() {
        ReviewTaskMapper mapper = mock(ReviewTaskMapper.class);
        ReviewTaskStateManager stateManager = new ReviewTaskStateManager(mapper);
        ReviewTask task = task();
        ArgumentCaptor<ReviewTask> taskCaptor = ArgumentCaptor.forClass(ReviewTask.class);

        stateManager.markRetrying(task, "temporary error");

        verify(mapper).updateById(taskCaptor.capture());
        assertThat(taskCaptor.getValue().getStatus()).isEqualTo("RUNNING");
        assertThat(taskCaptor.getValue().getErrorMessage()).isEqualTo("temporary error");
        assertThat(taskCaptor.getValue().getFinishedAt()).isNull();
    }

    private static ReviewTask task() {
        ReviewTask task = new ReviewTask();
        task.setId(1L);
        return task;
    }
}

package com.codepilot.module.review.state;

import com.codepilot.module.review.entity.ReviewTask;
import com.codepilot.module.review.mapper.ReviewTaskMapper;
import com.codepilot.module.review.config.ReviewProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReviewTaskStateManagerTest {

    @Test
    void shouldMarkTaskSuccessWithReviewSummary() {
        ReviewTaskMapper mapper = mock(ReviewTaskMapper.class);
        ReviewTaskStateManager stateManager = new ReviewTaskStateManager(mapper, new ReviewProperties());
        ReviewTask task = task();
        task.setExecutionToken("execution-1");
        when(mapper.completeExecution(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any())).thenReturn(1);

        stateManager.markSuccess(task, 3, 2, "HIGH");

        verify(mapper).completeExecution(
                org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq("execution-1"),
                org.mockito.ArgumentMatchers.eq(3),
                org.mockito.ArgumentMatchers.eq(2),
                org.mockito.ArgumentMatchers.eq("HIGH"),
                org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    void shouldMarkTaskRetryingWithoutFinishingIt() {
        ReviewTaskMapper mapper = mock(ReviewTaskMapper.class);
        ReviewTaskStateManager stateManager = new ReviewTaskStateManager(mapper, new ReviewProperties());
        ReviewTask task = task();
        task.setExecutionToken("execution-1");
        when(mapper.releaseForRetry(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any())).thenReturn(1);

        stateManager.markRetrying(task, "temporary error");

        verify(mapper).releaseForRetry(
                org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq("execution-1"),
                org.mockito.ArgumentMatchers.eq("temporary error"),
                org.mockito.ArgumentMatchers.any()
        );
    }

    private static ReviewTask task() {
        ReviewTask task = new ReviewTask();
        task.setId(1L);
        return task;
    }
}

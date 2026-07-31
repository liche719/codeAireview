package com.codepilot.module.review.publisher;

import com.codepilot.module.review.config.ReviewProperties;
import com.codepilot.module.review.entity.ReviewTask;
import com.codepilot.module.review.mapper.ReviewTaskMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReviewTaskPublicationServiceTest {

    @Test
    void shouldPersistFailedPublicationForRetry() {
        ReviewTaskMapper mapper = mock(ReviewTaskMapper.class);
        ReviewCommentPublisher publisher = mock(ReviewCommentPublisher.class);
        ReviewTask task = new ReviewTask();
        task.setId(1L);
        task.setPublishAttemptCount(1);
        when(mapper.claimPublication(eq(1L), any(), any())).thenReturn(1);
        when(mapper.selectById(1L)).thenReturn(task);
        when(publisher.publish(task)).thenReturn(ReviewCommentPublishResult.failedResult("GitHub unavailable"));
        ReviewTaskPublicationService service = new ReviewTaskPublicationService(
                mapper, publisher, new ReviewProperties(), new SimpleMeterRegistry()
        );

        service.publish(1L);

        verify(mapper).finishPublication(eq(1L), eq("FAILED"), eq("GitHub unavailable"), any(), any());
    }

    @Test
    void shouldStopAfterPublicationAttemptsAreExhausted() {
        ReviewTaskMapper mapper = mock(ReviewTaskMapper.class);
        ReviewCommentPublisher publisher = mock(ReviewCommentPublisher.class);
        ReviewTask task = new ReviewTask();
        task.setId(1L);
        task.setPublishAttemptCount(5);
        when(mapper.claimPublication(eq(1L), any(), any())).thenReturn(1);
        when(mapper.selectById(1L)).thenReturn(task);
        when(publisher.publish(task)).thenReturn(ReviewCommentPublishResult.failedResult("still unavailable"));

        new ReviewTaskPublicationService(
                mapper, publisher, new ReviewProperties(), new SimpleMeterRegistry()
        ).publish(1L);

        verify(mapper).finishPublication(eq(1L), eq("FAILED_FINAL"), eq("still unavailable"), eq(null), any());
    }
}

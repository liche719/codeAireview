package com.codepilot.module.review.queue;

import com.codepilot.module.review.config.ReviewProperties;
import com.codepilot.module.review.entity.ReviewTaskOutbox;
import com.codepilot.module.review.mapper.ReviewTaskOutboxMapper;
import com.codepilot.task.ReviewTaskProducer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReviewTaskOutboxPublisherTest {

    @Test
    void shouldMarkConfirmedMessagePublished() {
        ReviewTaskOutboxMapper mapper = mock(ReviewTaskOutboxMapper.class);
        ReviewTaskProducer producer = mock(ReviewTaskProducer.class);
        ReviewTaskOutbox message = message();
        when(mapper.claimDue(any(), any(), anyInt())).thenReturn(List.of(message));
        ReviewTaskOutboxPublisher publisher = new ReviewTaskOutboxPublisher(
                mapper, producer, new ReviewProperties(), new SimpleMeterRegistry()
        );

        publisher.publishDueMessages();

        verify(producer).sendAndConfirm(eq(7L), any());
        verify(mapper).markPublished(eq(3L), any(LocalDateTime.class));
    }

    @Test
    void shouldRescheduleMessageWhenPublisherConfirmFails() {
        ReviewTaskOutboxMapper mapper = mock(ReviewTaskOutboxMapper.class);
        ReviewTaskProducer producer = mock(ReviewTaskProducer.class);
        ReviewTaskOutbox message = message();
        when(mapper.claimDue(any(), any(), anyInt())).thenReturn(List.of(message));
        doThrow(new IllegalStateException("broker unavailable"))
                .when(producer).sendAndConfirm(eq(7L), any());
        ReviewTaskOutboxPublisher publisher = new ReviewTaskOutboxPublisher(
                mapper, producer, new ReviewProperties(), new SimpleMeterRegistry()
        );

        publisher.publishDueMessages();

        verify(mapper).markRetry(eq(3L), any(), eq("broker unavailable"), any());
    }

    @Test
    void shouldStopAfterOutboxAttemptsAreExhausted() {
        ReviewTaskOutboxMapper mapper = mock(ReviewTaskOutboxMapper.class);
        ReviewTaskProducer producer = mock(ReviewTaskProducer.class);
        ReviewTaskOutbox message = message();
        message.setAttemptCount(9);
        when(mapper.claimDue(any(), any(), anyInt())).thenReturn(List.of(message));
        doThrow(new IllegalStateException("broker unavailable"))
                .when(producer).sendAndConfirm(eq(7L), any());

        new ReviewTaskOutboxPublisher(
                mapper, producer, new ReviewProperties(), new SimpleMeterRegistry()
        ).publishDueMessages();

        verify(mapper).markFailed(eq(3L), eq("broker unavailable"), any());
    }

    private ReviewTaskOutbox message() {
        ReviewTaskOutbox message = new ReviewTaskOutbox();
        message.setId(3L);
        message.setTaskId(7L);
        message.setAttemptCount(0);
        return message;
    }
}

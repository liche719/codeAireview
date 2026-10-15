package com.codepilot.module.command.queue;

import com.codepilot.module.command.config.PrCommandTaskProperties;
import com.codepilot.module.command.entity.PrCommandTaskOutbox;
import com.codepilot.module.command.mapper.PrCommandTaskOutboxMapper;
import com.codepilot.task.PrCommandTaskProducer;
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

class PrCommandTaskOutboxPublisherTest {

    @Test
    void shouldPublishWithConfirmAndMarkOutboxPublished() {
        PrCommandTaskOutboxMapper mapper = mock(PrCommandTaskOutboxMapper.class);
        PrCommandTaskProducer producer = mock(PrCommandTaskProducer.class);
        when(mapper.claimDue(any(), any(), anyInt())).thenReturn(List.of(message()));
        PrCommandTaskOutboxPublisher publisher = new PrCommandTaskOutboxPublisher(
                mapper, producer, new PrCommandTaskProperties(), new SimpleMeterRegistry());

        publisher.publishDueMessages();

        verify(producer).sendAndConfirm(eq(7L), any());
        verify(mapper).markPublished(eq(3L), any(LocalDateTime.class));
    }

    @Test
    void shouldRetryWhenPublisherConfirmFails() {
        PrCommandTaskOutboxMapper mapper = mock(PrCommandTaskOutboxMapper.class);
        PrCommandTaskProducer producer = mock(PrCommandTaskProducer.class);
        when(mapper.claimDue(any(), any(), anyInt())).thenReturn(List.of(message()));
        doThrow(new IllegalStateException("broker unavailable"))
                .when(producer).sendAndConfirm(eq(7L), any());

        new PrCommandTaskOutboxPublisher(
                mapper, producer, new PrCommandTaskProperties(), new SimpleMeterRegistry())
                .publishDueMessages();

        verify(mapper).markRetry(eq(3L), any(), eq("broker unavailable"), any());
    }

    private PrCommandTaskOutbox message() {
        PrCommandTaskOutbox message = new PrCommandTaskOutbox();
        message.setId(3L);
        message.setCommandTaskId(7L);
        message.setAttemptCount(0);
        return message;
    }
}

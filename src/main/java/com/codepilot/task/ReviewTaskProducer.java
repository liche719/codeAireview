package com.codepilot.task;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReviewTaskProducer {

    public static final String REVIEW_TASK_QUEUE = "codepilot.review.task.queue";

    public static final String REVIEW_TASK_EXCHANGE = "codepilot.review.task.exchange";

    public static final String REVIEW_TASK_ROUTING_KEY = "codepilot.review.task";

    public static final String REVIEW_TASK_DEAD_LETTER_QUEUE = "codepilot.review.task.dlq";

    public static final String REVIEW_TASK_DEAD_LETTER_EXCHANGE = "codepilot.review.task.dlx";

    public static final String REVIEW_TASK_DEAD_LETTER_ROUTING_KEY = "codepilot.review.task.dead";

    private final RabbitTemplate rabbitTemplate;

    public void send(Long taskId) {
        sendAndConfirm(taskId, Duration.ofSeconds(5));
    }

    public void sendAndConfirm(Long taskId, Duration timeout) {
        ReviewTaskMessage message = new ReviewTaskMessage(taskId, UUID.randomUUID().toString());
        CorrelationData correlationData = new CorrelationData(message.getTraceId());
        rabbitTemplate.convertAndSend(REVIEW_TASK_EXCHANGE, REVIEW_TASK_ROUTING_KEY, message, correlationData);
        try {
            long timeoutMillis = Math.max(1L, timeout == null ? 5000L : timeout.toMillis());
            CorrelationData.Confirm confirm = correlationData.getFuture().get(timeoutMillis, TimeUnit.MILLISECONDS);
            if (!confirm.isAck()) {
                throw new AmqpException("review task message was not acknowledged: " + confirm.getReason());
            }
            ReturnedMessage returned = correlationData.getReturned();
            if (returned != null) {
                throw new AmqpException("review task message was returned: " + returned.getReplyText());
            }
        } catch (AmqpException exception) {
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AmqpException("review task message confirmation interrupted", exception);
        } catch (Exception exception) {
            throw new AmqpException("review task message confirmation failed", exception);
        }
        log.info("Review task message sent, taskId={}, traceId={}", message.getTaskId(), message.getTraceId());
    }
}

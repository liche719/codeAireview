package com.codepilot.task;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class PrCommandTaskProducer {

    public static final String PR_COMMAND_TASK_QUEUE = "codepilot.pr.command.task.queue";

    public static final String PR_COMMAND_TASK_EXCHANGE = "codepilot.pr.command.task.exchange";

    public static final String PR_COMMAND_TASK_ROUTING_KEY = "codepilot.pr.command.task";

    public static final String PR_COMMAND_TASK_DEAD_LETTER_QUEUE = "codepilot.pr.command.task.dlq";

    public static final String PR_COMMAND_TASK_DEAD_LETTER_EXCHANGE = "codepilot.pr.command.task.dlx";

    public static final String PR_COMMAND_TASK_DEAD_LETTER_ROUTING_KEY = "codepilot.pr.command.task.dead";

    private final RabbitTemplate rabbitTemplate;

    public void send(Long commandTaskId) {
        sendAndConfirm(commandTaskId, Duration.ofSeconds(5));
    }

    public void sendAndConfirm(Long commandTaskId, Duration timeout) {
        PrCommandTaskMessage message = new PrCommandTaskMessage(commandTaskId, UUID.randomUUID().toString());
        CorrelationData correlationData = new CorrelationData(message.getTraceId());
        rabbitTemplate.convertAndSend(
                PR_COMMAND_TASK_EXCHANGE, PR_COMMAND_TASK_ROUTING_KEY, message, correlationData);
        try {
            long timeoutMillis = Math.max(1L, timeout == null ? 5000L : timeout.toMillis());
            CorrelationData.Confirm confirm = correlationData.getFuture().get(timeoutMillis, TimeUnit.MILLISECONDS);
            if (!confirm.isAck()) {
                throw new AmqpException("PR command task message was not acknowledged: " + confirm.getReason());
            }
            ReturnedMessage returned = correlationData.getReturned();
            if (returned != null) {
                throw new AmqpException("PR command task message was returned: " + returned.getReplyText());
            }
        } catch (AmqpException exception) {
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AmqpException("PR command task message confirmation interrupted", exception);
        } catch (Exception exception) {
            throw new AmqpException("PR command task message confirmation failed", exception);
        }
        log.info("PR command task message sent, commandTaskId={}, traceId={}",
                message.getCommandTaskId(), message.getTraceId());
    }
}

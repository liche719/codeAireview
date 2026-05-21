package com.codepilot.task;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

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
        PrCommandTaskMessage message = new PrCommandTaskMessage(commandTaskId, UUID.randomUUID().toString());
        rabbitTemplate.convertAndSend(PR_COMMAND_TASK_EXCHANGE, PR_COMMAND_TASK_ROUTING_KEY, message);
        log.info("PR command task message sent, commandTaskId={}, traceId={}",
                message.getCommandTaskId(), message.getTraceId());
    }
}

package com.codepilot.task;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReviewTaskProducer {

    public static final String REVIEW_TASK_QUEUE = "codepilot.review.task.queue";

    public static final String REVIEW_TASK_EXCHANGE = "codepilot.review.task.exchange";

    public static final String REVIEW_TASK_ROUTING_KEY = "codepilot.review.task";

    private final RabbitTemplate rabbitTemplate;

    public void send(Long taskId) {
        ReviewTaskMessage message = new ReviewTaskMessage(taskId, UUID.randomUUID().toString());
        rabbitTemplate.convertAndSend(REVIEW_TASK_EXCHANGE, REVIEW_TASK_ROUTING_KEY, message);
        log.info("Review task message sent, taskId={}, traceId={}", message.getTaskId(), message.getTraceId());
    }
}


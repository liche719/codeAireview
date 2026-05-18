package com.codepilot.task;

import com.codepilot.module.review.service.ReviewTaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReviewTaskConsumer {

    private final ReviewTaskService reviewTaskService;

    @RabbitListener(queues = ReviewTaskProducer.REVIEW_TASK_QUEUE)
    public void consume(ReviewTaskMessage message) {
        log.info("Review task message consumed, taskId={}, traceId={}", message.getTaskId(), message.getTraceId());
        reviewTaskService.processTask(message.getTaskId());
    }
}

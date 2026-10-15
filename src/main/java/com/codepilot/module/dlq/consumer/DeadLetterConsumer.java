package com.codepilot.module.dlq.consumer;

import com.codepilot.module.dlq.service.DeadLetterService;
import com.codepilot.task.PrCommandTaskProducer;
import com.codepilot.task.ReviewTaskProducer;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 死信队列消费者：把最终失败的消息落库，使其可查询、可重放、可告警。
 *
 * <p>此前两个 DLQ 只有声明与绑定、**没有任何消费者**，消息进去就永久滞留。</p>
 *
 * <p><b>顺序约束</b>：必须先成功落库再 ack。落库失败时不 ack，消息留在 DLQ，
 * 由 {@code uq_dead_letter_digest_status} 唯一索引兜住重复——宁可重复也不丢。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "codepilot.dlq", name = "consumer-enabled", havingValue = "true", matchIfMissing = true)
public class DeadLetterConsumer {

    public static final String MESSAGE_TYPE_REVIEW_TASK = "REVIEW_TASK";

    public static final String MESSAGE_TYPE_PR_COMMAND_TASK = "PR_COMMAND_TASK";

    private final DeadLetterService deadLetterService;

    @RabbitListener(
            queues = ReviewTaskProducer.REVIEW_TASK_DEAD_LETTER_QUEUE,
            containerFactory = "deadLetterListenerContainerFactory"
    )
    public void consumeReviewTaskDeadLetter(Message message, Channel channel) throws IOException {
        String queue = ReviewTaskProducer.REVIEW_TASK_DEAD_LETTER_QUEUE;
        if (!persistOrKeep(queue, MESSAGE_TYPE_REVIEW_TASK, message)) {
            return;
        }
        channel.basicAck(deliveryTag(message), false);
    }

    @RabbitListener(
            queues = PrCommandTaskProducer.PR_COMMAND_TASK_DEAD_LETTER_QUEUE,
            containerFactory = "deadLetterListenerContainerFactory"
    )
    public void consumePrCommandTaskDeadLetter(Message message, Channel channel) throws IOException {
        String queue = PrCommandTaskProducer.PR_COMMAND_TASK_DEAD_LETTER_QUEUE;
        if (!persistOrKeep(queue, MESSAGE_TYPE_PR_COMMAND_TASK, message)) {
            return;
        }
        channel.basicAck(deliveryTag(message), false);
    }

    /**
     * @return true 表示已成功落库、可以 ack；false 表示落库失败、必须把消息留在 DLQ
     */
    private boolean persistOrKeep(String queue, String messageType, Message message) {
        try {
            deadLetterService.record(queue, messageType, message);
            return true;
        } catch (Exception exception) {
            log.error("Failed to persist dead letter, leaving it unacknowledged in the DLQ, "
                            + "queue={}, messageType={}, errorType={}",
                    queue, messageType, exception.getClass().getSimpleName());
            return false;
        }
    }

    private long deliveryTag(Message message) {
        return message.getMessageProperties().getDeliveryTag();
    }
}

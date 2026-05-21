package com.codepilot.common.config;

import com.codepilot.task.PrCommandTaskProducer;
import com.codepilot.task.ReviewTaskProducer;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;

import static org.assertj.core.api.Assertions.assertThat;

class RabbitMqConfigTest {

    private final RabbitMqConfig config = new RabbitMqConfig();

    @Test
    void shouldConfigureReviewTaskQueueWithDeadLetterRoute() {
        Queue queue = config.reviewTaskQueue();

        assertThat(queue.getName()).isEqualTo(ReviewTaskProducer.REVIEW_TASK_QUEUE);
        assertThat(queue.getArguments())
                .containsEntry("x-dead-letter-exchange", ReviewTaskProducer.REVIEW_TASK_DEAD_LETTER_EXCHANGE)
                .containsEntry("x-dead-letter-routing-key", ReviewTaskProducer.REVIEW_TASK_DEAD_LETTER_ROUTING_KEY);
    }

    @Test
    void shouldBindReviewTaskDeadLetterQueue() {
        Queue queue = config.reviewTaskDeadLetterQueue();
        DirectExchange exchange = config.reviewTaskDeadLetterExchange();
        Binding binding = config.reviewTaskDeadLetterBinding(queue, exchange);

        assertThat(queue.getName()).isEqualTo(ReviewTaskProducer.REVIEW_TASK_DEAD_LETTER_QUEUE);
        assertThat(exchange.getName()).isEqualTo(ReviewTaskProducer.REVIEW_TASK_DEAD_LETTER_EXCHANGE);
        assertThat(binding.getDestination()).isEqualTo(ReviewTaskProducer.REVIEW_TASK_DEAD_LETTER_QUEUE);
        assertThat(binding.getExchange()).isEqualTo(ReviewTaskProducer.REVIEW_TASK_DEAD_LETTER_EXCHANGE);
        assertThat(binding.getRoutingKey()).isEqualTo(ReviewTaskProducer.REVIEW_TASK_DEAD_LETTER_ROUTING_KEY);
    }

    @Test
    void shouldConfigurePrCommandTaskQueueWithDeadLetterRoute() {
        Queue queue = config.prCommandTaskQueue();

        assertThat(queue.getName()).isEqualTo(PrCommandTaskProducer.PR_COMMAND_TASK_QUEUE);
        assertThat(queue.getArguments())
                .containsEntry("x-dead-letter-exchange", PrCommandTaskProducer.PR_COMMAND_TASK_DEAD_LETTER_EXCHANGE)
                .containsEntry("x-dead-letter-routing-key", PrCommandTaskProducer.PR_COMMAND_TASK_DEAD_LETTER_ROUTING_KEY);
    }

    @Test
    void shouldBindPrCommandTaskDeadLetterQueue() {
        Queue queue = config.prCommandTaskDeadLetterQueue();
        DirectExchange exchange = config.prCommandTaskDeadLetterExchange();
        Binding binding = config.prCommandTaskDeadLetterBinding(queue, exchange);

        assertThat(queue.getName()).isEqualTo(PrCommandTaskProducer.PR_COMMAND_TASK_DEAD_LETTER_QUEUE);
        assertThat(exchange.getName()).isEqualTo(PrCommandTaskProducer.PR_COMMAND_TASK_DEAD_LETTER_EXCHANGE);
        assertThat(binding.getDestination()).isEqualTo(PrCommandTaskProducer.PR_COMMAND_TASK_DEAD_LETTER_QUEUE);
        assertThat(binding.getExchange()).isEqualTo(PrCommandTaskProducer.PR_COMMAND_TASK_DEAD_LETTER_EXCHANGE);
        assertThat(binding.getRoutingKey()).isEqualTo(PrCommandTaskProducer.PR_COMMAND_TASK_DEAD_LETTER_ROUTING_KEY);
    }
}

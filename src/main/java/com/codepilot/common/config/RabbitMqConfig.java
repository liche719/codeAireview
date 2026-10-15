package com.codepilot.common.config;

import com.codepilot.task.ReviewTaskProducer;
import com.codepilot.task.PrCommandTaskProducer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerContainerFactory;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMqConfig {

    @Bean
    public Queue reviewTaskQueue() {
        return QueueBuilder.durable(ReviewTaskProducer.REVIEW_TASK_QUEUE)
                .deadLetterExchange(ReviewTaskProducer.REVIEW_TASK_DEAD_LETTER_EXCHANGE)
                .deadLetterRoutingKey(ReviewTaskProducer.REVIEW_TASK_DEAD_LETTER_ROUTING_KEY)
                .build();
    }

    @Bean
    public DirectExchange reviewTaskExchange() {
        return new DirectExchange(ReviewTaskProducer.REVIEW_TASK_EXCHANGE, true, false);
    }

    @Bean
    public Queue reviewTaskDeadLetterQueue() {
        return QueueBuilder.durable(ReviewTaskProducer.REVIEW_TASK_DEAD_LETTER_QUEUE).build();
    }

    @Bean
    public DirectExchange reviewTaskDeadLetterExchange() {
        return new DirectExchange(ReviewTaskProducer.REVIEW_TASK_DEAD_LETTER_EXCHANGE, true, false);
    }

    @Bean
    public Binding reviewTaskBinding(
            @Qualifier("reviewTaskQueue") Queue reviewTaskQueue,
            @Qualifier("reviewTaskExchange") DirectExchange reviewTaskExchange
    ) {
        return BindingBuilder.bind(reviewTaskQueue)
                .to(reviewTaskExchange)
                .with(ReviewTaskProducer.REVIEW_TASK_ROUTING_KEY);
    }

    @Bean
    public Binding reviewTaskDeadLetterBinding(
            @Qualifier("reviewTaskDeadLetterQueue") Queue reviewTaskDeadLetterQueue,
            @Qualifier("reviewTaskDeadLetterExchange") DirectExchange reviewTaskDeadLetterExchange
    ) {
        return BindingBuilder.bind(reviewTaskDeadLetterQueue)
                .to(reviewTaskDeadLetterExchange)
                .with(ReviewTaskProducer.REVIEW_TASK_DEAD_LETTER_ROUTING_KEY);
    }

    @Bean
    public Queue prCommandTaskQueue() {
        return QueueBuilder.durable(PrCommandTaskProducer.PR_COMMAND_TASK_QUEUE)
                .deadLetterExchange(PrCommandTaskProducer.PR_COMMAND_TASK_DEAD_LETTER_EXCHANGE)
                .deadLetterRoutingKey(PrCommandTaskProducer.PR_COMMAND_TASK_DEAD_LETTER_ROUTING_KEY)
                .build();
    }

    @Bean
    public DirectExchange prCommandTaskExchange() {
        return new DirectExchange(PrCommandTaskProducer.PR_COMMAND_TASK_EXCHANGE, true, false);
    }

    @Bean
    public Queue prCommandTaskDeadLetterQueue() {
        return QueueBuilder.durable(PrCommandTaskProducer.PR_COMMAND_TASK_DEAD_LETTER_QUEUE).build();
    }

    @Bean
    public DirectExchange prCommandTaskDeadLetterExchange() {
        return new DirectExchange(PrCommandTaskProducer.PR_COMMAND_TASK_DEAD_LETTER_EXCHANGE, true, false);
    }

    @Bean
    public Binding prCommandTaskBinding(
            @Qualifier("prCommandTaskQueue") Queue prCommandTaskQueue,
            @Qualifier("prCommandTaskExchange") DirectExchange prCommandTaskExchange
    ) {
        return BindingBuilder.bind(prCommandTaskQueue)
                .to(prCommandTaskExchange)
                .with(PrCommandTaskProducer.PR_COMMAND_TASK_ROUTING_KEY);
    }

    @Bean
    public Binding prCommandTaskDeadLetterBinding(
            @Qualifier("prCommandTaskDeadLetterQueue") Queue prCommandTaskDeadLetterQueue,
            @Qualifier("prCommandTaskDeadLetterExchange") DirectExchange prCommandTaskDeadLetterExchange
    ) {
        return BindingBuilder.bind(prCommandTaskDeadLetterQueue)
                .to(prCommandTaskDeadLetterExchange)
                .with(PrCommandTaskProducer.PR_COMMAND_TASK_DEAD_LETTER_ROUTING_KEY);
    }

    @Bean
    public MessageConverter jackson2JsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory, MessageConverter messageConverter) {
        RabbitTemplate rabbitTemplate = new RabbitTemplate(connectionFactory);
        rabbitTemplate.setMessageConverter(messageConverter);
        rabbitTemplate.setMandatory(true);
        return rabbitTemplate;
    }

    @Bean
    public RabbitListenerContainerFactory<?> rabbitListenerContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer,
            ConnectionFactory connectionFactory,
            MessageConverter messageConverter
    ) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setMessageConverter(messageConverter);
        return factory;
    }

    /**
     * 死信队列专用容器工厂：**手动 ack**。
     *
     * <p>死信消费者必须先把消息成功落库、再 ack。若沿用自动 ack，落库抛异常时消息会被 reject，
     * 而 DLQ 自身没有再配死信交换，消息就**直接丢失**——这比重复消费更糟。
     * 手动 ack 让"落库失败 ⇒ 不 ack ⇒ 消息留在 DLQ"成为可能。</p>
     */
    @Bean
    public RabbitListenerContainerFactory<?> deadLetterListenerContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer,
            ConnectionFactory connectionFactory,
            MessageConverter messageConverter
    ) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setMessageConverter(messageConverter);
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        return factory;
    }
}

package com.codepilot.common.config;

import com.codepilot.task.ReviewTaskProducer;
import com.codepilot.task.PrCommandTaskProducer;
import org.springframework.beans.factory.annotation.Qualifier;
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
        return QueueBuilder.durable(ReviewTaskProducer.REVIEW_TASK_QUEUE).build();
    }

    @Bean
    public DirectExchange reviewTaskExchange() {
        return new DirectExchange(ReviewTaskProducer.REVIEW_TASK_EXCHANGE, true, false);
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
    public Queue prCommandTaskQueue() {
        return QueueBuilder.durable(PrCommandTaskProducer.PR_COMMAND_TASK_QUEUE).build();
    }

    @Bean
    public DirectExchange prCommandTaskExchange() {
        return new DirectExchange(PrCommandTaskProducer.PR_COMMAND_TASK_EXCHANGE, true, false);
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
    public MessageConverter jackson2JsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory, MessageConverter messageConverter) {
        RabbitTemplate rabbitTemplate = new RabbitTemplate(connectionFactory);
        rabbitTemplate.setMessageConverter(messageConverter);
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
}

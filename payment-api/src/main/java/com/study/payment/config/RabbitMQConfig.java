package com.study.payment.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    public static final String EXCHANGE      = "payments.exchange";
    public static final String DLX           = "payments.dlx";
    public static final String QUEUE         = "payment.processed.queue";
    public static final String DLQ           = "payment.processed.dlq";
    public static final String ROUTING_KEY   = "payment.processed";

    @Bean
    public TopicExchange paymentsExchange() {
        return ExchangeBuilder.topicExchange(EXCHANGE).durable(true).build();
    }

    @Bean
    public DirectExchange paymentsDlx() {
        return ExchangeBuilder.directExchange(DLX).durable(true).build();
    }

    @Bean
    public Queue paymentProcessedQueue() {
        return QueueBuilder.durable(QUEUE)
                .withArgument("x-dead-letter-exchange", DLX)
                .build();
    }

    @Bean
    public Queue paymentProcessedDlq() {
        return QueueBuilder.durable(DLQ).build();
    }

    @Bean
    public Binding paymentProcessedBinding(Queue paymentProcessedQueue, TopicExchange paymentsExchange) {
        return BindingBuilder.bind(paymentProcessedQueue).to(paymentsExchange).with(ROUTING_KEY);
    }

    @Bean
    public MessageConverter messageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory, MessageConverter messageConverter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(messageConverter);
        return template;
    }
}

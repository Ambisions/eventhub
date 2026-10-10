package com.eventhub.notification.config;

import com.eventhub.notification.rabbit.NotificationRabbitTopology;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class NotificationRabbitConfiguration {

    @Bean
    DirectExchange notificationExchange() {
        return new DirectExchange(NotificationRabbitTopology.EXCHANGE, true, false);
    }

    @Bean
    Queue ticketIssuedNotificationQueue() {
        return QueueBuilder.durable(NotificationRabbitTopology.TICKET_ISSUED_QUEUE).build();
    }

    @Bean
    Binding ticketIssuedNotificationBinding(
            DirectExchange notificationExchange,
            Queue ticketIssuedNotificationQueue) {
        return BindingBuilder.bind(ticketIssuedNotificationQueue)
                .to(notificationExchange)
                .with(NotificationRabbitTopology.TICKET_ISSUED_ROUTING_KEY);
    }

    @Bean
    MessageConverter notificationMessageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(
                objectMapper,
                "com.eventhub.notification.api.event");
    }
}

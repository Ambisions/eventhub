package com.eventhub.notification.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;

class NotificationRabbitConfigurationTest {

    private final NotificationRabbitConfiguration configuration = new NotificationRabbitConfiguration();

    @Test
    void declaresFrozenDurableTopology() {
        DirectExchange exchange = configuration.notificationExchange();
        Queue queue = configuration.ticketIssuedNotificationQueue();
        Binding binding = configuration.ticketIssuedNotificationBinding(exchange, queue);

        assertEquals("eventhub.notification.v1", exchange.getName());
        assertEquals("direct", exchange.getType());
        assertTrue(exchange.isDurable());
        assertFalse(exchange.isAutoDelete());
        assertEquals("eventhub.notification.ticket-issued.v1", queue.getName());
        assertTrue(queue.isDurable());
        assertFalse(queue.isExclusive());
        assertFalse(queue.isAutoDelete());
        assertEquals(exchange.getName(), binding.getExchange());
        assertEquals(queue.getName(), binding.getDestination());
        assertEquals("ticket.issued.v1", binding.getRoutingKey());
    }
}

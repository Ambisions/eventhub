package com.eventhub.notification.rabbit;

import com.eventhub.notification.api.NotificationEventPublisherApi;
import com.eventhub.notification.api.error.NotificationPublishException;
import com.eventhub.notification.api.event.TicketIssuedEvent;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Component
public final class RabbitNotificationEventPublisher implements NotificationEventPublisherApi {

    private final RabbitTemplate rabbitTemplate;

    public RabbitNotificationEventPublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    @Override
    public void publishTicketIssued(TicketIssuedEvent event) {
        try {
            rabbitTemplate.convertAndSend(
                    NotificationRabbitTopology.EXCHANGE,
                    NotificationRabbitTopology.TICKET_ISSUED_ROUTING_KEY,
                    event);
        } catch (AmqpException exception) {
            throw new NotificationPublishException(exception);
        }
    }
}

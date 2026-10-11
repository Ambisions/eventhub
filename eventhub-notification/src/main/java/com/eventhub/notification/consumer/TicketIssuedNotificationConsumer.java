package com.eventhub.notification.consumer;

import com.eventhub.notification.api.event.TicketIssuedEvent;
import com.eventhub.notification.rabbit.NotificationRabbitTopology;
import com.eventhub.notification.service.UserNotificationService;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

@Component
public class TicketIssuedNotificationConsumer {

    private final UserNotificationService userNotificationService;

    public TicketIssuedNotificationConsumer(UserNotificationService userNotificationService) {
        this.userNotificationService = userNotificationService;
    }

    @RabbitListener(queues = NotificationRabbitTopology.TICKET_ISSUED_QUEUE)
    public void consume(TicketIssuedEvent event) {
        try {
            userNotificationService.recordTicketIssued(event);
        } catch (DuplicateKeyException exception) {
            userNotificationService.verifyEquivalentDuplicate(event, exception);
        }
    }
}

package com.eventhub.application.notification;

import java.time.Clock;
import java.util.UUID;

import com.eventhub.notification.api.NotificationEventPublisherApi;
import com.eventhub.notification.api.event.TicketIssuedEvent;
import com.eventhub.order.api.OrderCommandApi;
import com.eventhub.order.api.model.PaymentResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public final class PaymentNotificationApplicationService {

    private static final Logger LOGGER = LoggerFactory.getLogger(PaymentNotificationApplicationService.class);

    private final OrderCommandApi orderCommandApi;
    private final NotificationEventPublisherApi notificationEventPublisherApi;
    private final Clock clock;

    public PaymentNotificationApplicationService(
            OrderCommandApi orderCommandApi,
            NotificationEventPublisherApi notificationEventPublisherApi,
            Clock clock) {
        this.orderCommandApi = orderCommandApi;
        this.notificationEventPublisherApi = notificationEventPublisherApi;
        this.clock = clock;
    }

    public PaymentResult pay(long userId, long orderId) {
        PaymentResult result = orderCommandApi.pay(userId, orderId);

        try {
            TicketIssuedEvent event = new TicketIssuedEvent(
                    UUID.randomUUID(),
                    1,
                    userId,
                    result.order().id(),
                    result.order().sessionId(),
                    result.ticket().ticketNo(),
                    clock.instant());
            notificationEventPublisherApi.publishTicketIssued(event);
        } catch (RuntimeException exception) {
            LOGGER.warn(
                    "Notification publish failed after Pay commit for eventType=TICKET_ISSUED orderId={} "
                            + "userId={} cause={}",
                    orderId,
                    userId,
                    exception.getClass().getSimpleName());
        }

        return result;
    }
}

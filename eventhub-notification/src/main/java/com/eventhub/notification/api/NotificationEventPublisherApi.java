package com.eventhub.notification.api;

import com.eventhub.notification.api.event.TicketIssuedEvent;

public interface NotificationEventPublisherApi {

    void publishTicketIssued(TicketIssuedEvent event);
}

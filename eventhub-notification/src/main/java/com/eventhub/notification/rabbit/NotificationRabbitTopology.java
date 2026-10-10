package com.eventhub.notification.rabbit;

public final class NotificationRabbitTopology {

    public static final String EXCHANGE = "eventhub.notification.v1";
    public static final String TICKET_ISSUED_ROUTING_KEY = "ticket.issued.v1";
    public static final String TICKET_ISSUED_QUEUE = "eventhub.notification.ticket-issued.v1";

    private NotificationRabbitTopology() {
    }
}

package com.eventhub.ticket.api.model;

import java.time.LocalDateTime;

public record TicketView(
        String ticketNo,
        long orderId,
        long sessionId,
        TicketStatus status,
        LocalDateTime createdAt) {
}

package com.eventhub.ticket.api.model;

public record TicketCheckInTarget(
        String ticketNo,
        long sessionId) {
}

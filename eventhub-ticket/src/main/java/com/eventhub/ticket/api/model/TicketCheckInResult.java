package com.eventhub.ticket.api.model;

public record TicketCheckInResult(
        String ticketNo,
        long sessionId,
        TicketStatus status) {
}

package com.eventhub.notification.api.event;

import java.time.Instant;
import java.util.UUID;

public record TicketIssuedEvent(
        UUID eventId,
        int schemaVersion,
        long userId,
        long orderId,
        long sessionId,
        String ticketNo,
        Instant occurredAt) {

    public TicketIssuedEvent {
        if (eventId == null) {
            throw new IllegalArgumentException("eventId must not be null");
        }
        if (schemaVersion != 1) {
            throw new IllegalArgumentException("schemaVersion must be 1");
        }
        if (userId <= 0) {
            throw new IllegalArgumentException("userId must be positive");
        }
        if (orderId <= 0) {
            throw new IllegalArgumentException("orderId must be positive");
        }
        if (sessionId <= 0) {
            throw new IllegalArgumentException("sessionId must be positive");
        }
        if (ticketNo == null || ticketNo.isBlank()) {
            throw new IllegalArgumentException("ticketNo must not be blank");
        }
        if (occurredAt == null) {
            throw new IllegalArgumentException("occurredAt must not be null");
        }
    }
}

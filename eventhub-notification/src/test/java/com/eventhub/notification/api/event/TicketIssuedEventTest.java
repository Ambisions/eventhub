package com.eventhub.notification.api.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class TicketIssuedEventTest {

    private static final UUID EVENT_ID = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
    private static final Instant OCCURRED_AT = Instant.parse("2026-10-10T10:15:30Z");

    @Test
    void preservesFrozenContractFields() {
        TicketIssuedEvent event = validEvent();

        assertEquals(EVENT_ID, event.eventId());
        assertEquals(1, event.schemaVersion());
        assertEquals(11L, event.userId());
        assertEquals(22L, event.orderId());
        assertEquals(33L, event.sessionId());
        assertEquals("TKT-123", event.ticketNo());
        assertEquals(OCCURRED_AT, event.occurredAt());
    }

    @Test
    void rejectsInvalidFields() {
        assertThrows(IllegalArgumentException.class,
                () -> new TicketIssuedEvent(null, 1, 11, 22, 33, "TKT-123", OCCURRED_AT));
        assertThrows(IllegalArgumentException.class,
                () -> new TicketIssuedEvent(EVENT_ID, 2, 11, 22, 33, "TKT-123", OCCURRED_AT));
        assertThrows(IllegalArgumentException.class,
                () -> new TicketIssuedEvent(EVENT_ID, 1, 0, 22, 33, "TKT-123", OCCURRED_AT));
        assertThrows(IllegalArgumentException.class,
                () -> new TicketIssuedEvent(EVENT_ID, 1, 11, 0, 33, "TKT-123", OCCURRED_AT));
        assertThrows(IllegalArgumentException.class,
                () -> new TicketIssuedEvent(EVENT_ID, 1, 11, 22, 0, "TKT-123", OCCURRED_AT));
        assertThrows(IllegalArgumentException.class,
                () -> new TicketIssuedEvent(EVENT_ID, 1, 11, 22, 33, "  ", OCCURRED_AT));
        assertThrows(IllegalArgumentException.class,
                () -> new TicketIssuedEvent(EVENT_ID, 1, 11, 22, 33, "TKT-123", null));
    }

    private static TicketIssuedEvent validEvent() {
        return new TicketIssuedEvent(EVENT_ID, 1, 11, 22, 33, "TKT-123", OCCURRED_AT);
    }
}

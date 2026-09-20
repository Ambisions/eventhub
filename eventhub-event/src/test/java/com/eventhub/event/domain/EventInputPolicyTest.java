package com.eventhub.event.domain;

import com.eventhub.event.api.error.EventErrorCode;
import com.eventhub.event.api.error.EventException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EventInputPolicyTest {

    @Test
    void normalizesValidTitleAndRejectionReason() {
        assertEquals("Event title", EventInputPolicy.normalizeTitle("  Event title  "));
        assertEquals("x", EventInputPolicy.normalizeTitle("x"));
        assertEquals(200, EventInputPolicy.normalizeTitle("x".repeat(200)).length());

        assertEquals("reason", EventInputPolicy.normalizeRejectionReason("  reason  "));
        assertEquals("x", EventInputPolicy.normalizeRejectionReason("x"));
        assertEquals(500, EventInputPolicy.normalizeRejectionReason("x".repeat(500)).length());
    }

    @Test
    void rejectsInvalidTitles() {
        assertEventError(() -> EventInputPolicy.normalizeTitle(null), EventErrorCode.INVALID_EVENT);
        assertEventError(() -> EventInputPolicy.normalizeTitle(""), EventErrorCode.INVALID_EVENT);
        assertEventError(() -> EventInputPolicy.normalizeTitle("   "), EventErrorCode.INVALID_EVENT);
        assertEventError(() -> EventInputPolicy.normalizeTitle("x".repeat(201)), EventErrorCode.INVALID_EVENT);
    }

    @Test
    void rejectsInvalidRejectionReasons() {
        assertEventError(
                () -> EventInputPolicy.normalizeRejectionReason(null),
                EventErrorCode.INVALID_REJECTION_REASON);
        assertEventError(
                () -> EventInputPolicy.normalizeRejectionReason(""),
                EventErrorCode.INVALID_REJECTION_REASON);
        assertEventError(
                () -> EventInputPolicy.normalizeRejectionReason("   "),
                EventErrorCode.INVALID_REJECTION_REASON);
        assertEventError(
                () -> EventInputPolicy.normalizeRejectionReason("x".repeat(501)),
                EventErrorCode.INVALID_REJECTION_REASON);
    }

    private static void assertEventError(Runnable action, EventErrorCode expected) {
        EventException exception = assertThrows(EventException.class, action::run);
        assertEquals(expected, exception.getErrorCode());
    }
}

package com.eventhub.event.domain;

import java.time.LocalDateTime;

import com.eventhub.event.api.error.EventErrorCode;
import com.eventhub.event.api.error.EventException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EventSessionInputPolicyTest {

    @Test
    void normalizesNameAndVenueAtTheirBoundaries() {
        assertEquals("Main session", EventSessionInputPolicy.normalizeName("  Main session  "));
        assertEquals(200, EventSessionInputPolicy.normalizeName("x".repeat(200)).length());
        assertNull(EventSessionInputPolicy.normalizeVenue(null));
        assertNull(EventSessionInputPolicy.normalizeVenue("   "));
        assertEquals("Hall A", EventSessionInputPolicy.normalizeVenue("  Hall A  "));
        assertEquals(255, EventSessionInputPolicy.normalizeVenue("x".repeat(255)).length());
    }

    @Test
    void rejectsInvalidNamesAndVenues() {
        assertInvalid(() -> EventSessionInputPolicy.normalizeName(null));
        assertInvalid(() -> EventSessionInputPolicy.normalizeName("   "));
        assertInvalid(() -> EventSessionInputPolicy.normalizeName("x".repeat(201)));
        assertInvalid(() -> EventSessionInputPolicy.normalizeVenue("x".repeat(256)));
    }

    @Test
    void acceptsOnlyStrictlyIncreasingSaleAndRuntimeWindows() {
        LocalDateTime base = LocalDateTime.of(2030, 1, 1, 10, 0);
        assertDoesNotThrow(() -> EventSessionInputPolicy.validateTimes(
                base, base.plusHours(1), base.plusHours(2), base.plusHours(3)));

        assertInvalid(() -> EventSessionInputPolicy.validateTimes(
                base, base, base.plusHours(2), base.plusHours(3)));
        assertInvalid(() -> EventSessionInputPolicy.validateTimes(
                base, base.plusHours(1), base.plusHours(3), base.plusHours(3)));
        assertInvalid(() -> EventSessionInputPolicy.validateTimes(
                null, base.plusHours(1), base.plusHours(2), base.plusHours(3)));
    }

    private static void assertInvalid(Runnable action) {
        EventException exception = assertThrows(EventException.class, action::run);
        assertEquals(EventErrorCode.INVALID_SESSION, exception.getErrorCode());
    }
}

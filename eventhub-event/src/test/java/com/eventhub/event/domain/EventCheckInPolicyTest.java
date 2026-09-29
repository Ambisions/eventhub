package com.eventhub.event.domain;

import java.time.LocalDateTime;

import com.eventhub.event.api.error.EventCheckInAuthorizationException;
import com.eventhub.event.api.error.EventCheckInAuthorizationFailure;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EventCheckInPolicyTest {

    private static final LocalDateTime START = LocalDateTime.of(2030, 6, 1, 10, 0);
    private static final LocalDateTime END = START.plusHours(2);

    @Test
    void rejectsBeforeStart() {
        assertFailure(START.minusNanos(1_000), EventCheckInAuthorizationFailure.NOT_STARTED);
    }

    @Test
    void allowsExactStartAndInsideWindow() {
        assertDoesNotThrow(() -> EventCheckInPolicy.requireEligible(START, START, END));
        assertDoesNotThrow(() -> EventCheckInPolicy.requireEligible(START.plusHours(1), START, END));
    }

    @Test
    void rejectsExactEndAndAfterEnd() {
        assertFailure(END, EventCheckInAuthorizationFailure.CLOSED);
        assertFailure(END.plusNanos(1_000), EventCheckInAuthorizationFailure.CLOSED);
    }

    private static void assertFailure(
            LocalDateTime checkInTime,
            EventCheckInAuthorizationFailure expected) {
        EventCheckInAuthorizationException exception = assertThrows(
                EventCheckInAuthorizationException.class,
                () -> EventCheckInPolicy.requireEligible(checkInTime, START, END));
        assertEquals(expected, exception.getFailure());
    }
}

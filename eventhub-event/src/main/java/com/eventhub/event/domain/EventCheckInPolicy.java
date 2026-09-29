package com.eventhub.event.domain;

import java.time.LocalDateTime;
import java.util.Objects;

import com.eventhub.event.api.error.EventCheckInAuthorizationException;
import com.eventhub.event.api.error.EventCheckInAuthorizationFailure;

public final class EventCheckInPolicy {

    private EventCheckInPolicy() {
    }

    public static void requireEligible(
            LocalDateTime checkInTime,
            LocalDateTime sessionStartAt,
            LocalDateTime sessionEndAt) {
        Objects.requireNonNull(checkInTime, "checkInTime must not be null");
        Objects.requireNonNull(sessionStartAt, "sessionStartAt must not be null");
        Objects.requireNonNull(sessionEndAt, "sessionEndAt must not be null");

        if (checkInTime.isBefore(sessionStartAt)) {
            throw new EventCheckInAuthorizationException(EventCheckInAuthorizationFailure.NOT_STARTED);
        }
        if (!checkInTime.isBefore(sessionEndAt)) {
            throw new EventCheckInAuthorizationException(EventCheckInAuthorizationFailure.CLOSED);
        }
    }
}

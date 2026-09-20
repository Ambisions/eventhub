package com.eventhub.event.domain;

import java.time.LocalDateTime;

import com.eventhub.event.api.error.EventErrorCode;
import com.eventhub.event.api.error.EventException;

public final class EventSessionInputPolicy {

    private static final int MAX_NAME_LENGTH = 200;
    private static final int MAX_VENUE_LENGTH = 255;

    private EventSessionInputPolicy() {
    }

    public static String normalizeName(String name) {
        if (name == null) {
            throw invalidSession();
        }
        String normalized = name.trim();
        if (normalized.isEmpty() || normalized.length() > MAX_NAME_LENGTH) {
            throw invalidSession();
        }
        return normalized;
    }

    public static String normalizeVenue(String venue) {
        if (venue == null) {
            return null;
        }
        String normalized = venue.trim();
        if (normalized.isEmpty()) {
            return null;
        }
        if (normalized.length() > MAX_VENUE_LENGTH) {
            throw invalidSession();
        }
        return normalized;
    }

    public static void validateTimes(
            LocalDateTime saleStartAt,
            LocalDateTime saleEndAt,
            LocalDateTime startAt,
            LocalDateTime endAt) {
        if (saleStartAt == null || saleEndAt == null || startAt == null || endAt == null
                || !saleStartAt.isBefore(saleEndAt)
                || !startAt.isBefore(endAt)) {
            throw invalidSession();
        }
    }

    private static EventException invalidSession() {
        return new EventException(EventErrorCode.INVALID_SESSION);
    }
}

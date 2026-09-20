package com.eventhub.event.domain;

import com.eventhub.event.api.error.EventErrorCode;
import com.eventhub.event.api.error.EventException;

public final class EventInputPolicy {

    private static final int MAX_TITLE_LENGTH = 200;
    private static final int MAX_REJECTION_REASON_LENGTH = 500;

    private EventInputPolicy() {
    }

    public static String normalizeTitle(String title) {
        if (title == null) {
            throw new EventException(EventErrorCode.INVALID_EVENT);
        }
        String normalized = title.trim();
        if (normalized.isEmpty() || normalized.length() > MAX_TITLE_LENGTH) {
            throw new EventException(EventErrorCode.INVALID_EVENT);
        }
        return normalized;
    }

    public static String normalizeRejectionReason(String reason) {
        if (reason == null) {
            throw new EventException(EventErrorCode.INVALID_REJECTION_REASON);
        }
        String normalized = reason.trim();
        if (normalized.isEmpty() || normalized.length() > MAX_REJECTION_REASON_LENGTH) {
            throw new EventException(EventErrorCode.INVALID_REJECTION_REASON);
        }
        return normalized;
    }
}

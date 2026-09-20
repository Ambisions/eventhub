package com.eventhub.event.domain;

import com.eventhub.event.api.model.EventStatus;

public final class EventStatePolicy {

    private EventStatePolicy() {
    }

    public static boolean isEditable(EventStatus status) {
        return status == EventStatus.DRAFT || status == EventStatus.REJECTED;
    }

    public static boolean canSubmit(EventStatus status) {
        return isEditable(status);
    }

    public static boolean canReview(EventStatus status) {
        return status == EventStatus.PENDING_REVIEW;
    }
}

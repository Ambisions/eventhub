package com.eventhub.event.api.model;

import java.time.LocalDateTime;

public record ManagedEventView(
        long id,
        String title,
        String description,
        EventStatus status,
        String rejectionReason,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}

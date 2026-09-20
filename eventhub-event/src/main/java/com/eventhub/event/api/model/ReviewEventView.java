package com.eventhub.event.api.model;

import java.time.LocalDateTime;

public record ReviewEventView(
        long id,
        long organizerUserId,
        String title,
        String description,
        EventStatus status,
        String rejectionReason,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}

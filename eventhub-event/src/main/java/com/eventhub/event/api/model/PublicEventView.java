package com.eventhub.event.api.model;

import java.time.LocalDateTime;

public record PublicEventView(
        long id,
        String title,
        String description,
        EventStatus status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}

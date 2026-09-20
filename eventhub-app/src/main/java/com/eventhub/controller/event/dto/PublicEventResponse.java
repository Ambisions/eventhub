package com.eventhub.controller.event.dto;

import java.time.LocalDateTime;

import com.eventhub.event.api.model.EventStatus;
import com.eventhub.event.api.model.PublicEventView;

public record PublicEventResponse(
        long id,
        String title,
        String description,
        EventStatus status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public static PublicEventResponse from(PublicEventView view) {
        return new PublicEventResponse(view.id(), view.title(), view.description(), view.status(),
                view.createdAt(), view.updatedAt());
    }
}

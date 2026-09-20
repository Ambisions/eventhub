package com.eventhub.controller.event.dto;

import java.time.LocalDateTime;

import com.eventhub.event.api.model.EventSessionView;

public record EventSessionResponse(
        long id,
        long eventId,
        String name,
        String venue,
        LocalDateTime saleStartAt,
        LocalDateTime saleEndAt,
        LocalDateTime startAt,
        LocalDateTime endAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public static EventSessionResponse from(EventSessionView view) {
        return new EventSessionResponse(view.id(), view.eventId(), view.name(), view.venue(),
                view.saleStartAt(), view.saleEndAt(), view.startAt(), view.endAt(),
                view.createdAt(), view.updatedAt());
    }
}

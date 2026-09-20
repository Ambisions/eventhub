package com.eventhub.controller.event.dto;

import java.time.LocalDateTime;

import com.eventhub.event.api.model.EventStatus;
import com.eventhub.event.api.model.ReviewEventView;

public record ReviewEventResponse(
        long id,
        long organizerUserId,
        String title,
        String description,
        EventStatus status,
        String rejectionReason,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public static ReviewEventResponse from(ReviewEventView view) {
        return new ReviewEventResponse(view.id(), view.organizerUserId(), view.title(),
                view.description(), view.status(), view.rejectionReason(),
                view.createdAt(), view.updatedAt());
    }
}

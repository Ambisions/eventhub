package com.eventhub.controller.event.dto;

import java.time.LocalDateTime;

import com.eventhub.event.api.model.EventStatus;
import com.eventhub.event.api.model.ManagedEventView;

public record ManagedEventResponse(
        long id,
        String title,
        String description,
        EventStatus status,
        String rejectionReason,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public static ManagedEventResponse from(ManagedEventView view) {
        return new ManagedEventResponse(view.id(), view.title(), view.description(), view.status(),
                view.rejectionReason(), view.createdAt(), view.updatedAt());
    }
}

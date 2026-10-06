package com.eventhub.event.api.model;

import java.time.LocalDateTime;

public record EventSessionView(
        long id,
        long eventId,
        String name,
        String venue,
        SessionSaleMode saleMode,
        LocalDateTime saleStartAt,
        LocalDateTime saleEndAt,
        LocalDateTime startAt,
        LocalDateTime endAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}

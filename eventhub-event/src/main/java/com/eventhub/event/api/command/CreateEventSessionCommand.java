package com.eventhub.event.api.command;

import java.time.LocalDateTime;

import com.eventhub.event.api.model.SessionSaleMode;

public record CreateEventSessionCommand(
        String name,
        String venue,
        SessionSaleMode saleMode,
        LocalDateTime saleStartAt,
        LocalDateTime saleEndAt,
        LocalDateTime startAt,
        LocalDateTime endAt) {
}

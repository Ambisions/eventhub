package com.eventhub.event.api.model;

import java.time.LocalDateTime;

public record FlashPreparationEventContext(
        long eventId,
        long sessionId,
        EventStatus eventStatus,
        SessionSaleMode saleMode,
        LocalDateTime saleStartAt,
        LocalDateTime saleEndAt) {
}

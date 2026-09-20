package com.eventhub.event.api.command;

import java.time.LocalDateTime;

public record UpdateEventSessionCommand(
        String name,
        String venue,
        LocalDateTime saleStartAt,
        LocalDateTime saleEndAt,
        LocalDateTime startAt,
        LocalDateTime endAt) {
}

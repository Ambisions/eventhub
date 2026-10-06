package com.eventhub.controller.event.dto;

import java.time.LocalDateTime;

import com.eventhub.event.api.model.SessionSaleMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateEventSessionRequest(
        @NotBlank String name,
        String venue,
        SessionSaleMode saleMode,
        @NotNull LocalDateTime saleStartAt,
        @NotNull LocalDateTime saleEndAt,
        @NotNull LocalDateTime startAt,
        @NotNull LocalDateTime endAt) {
}

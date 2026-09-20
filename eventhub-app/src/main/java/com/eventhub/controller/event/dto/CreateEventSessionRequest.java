package com.eventhub.controller.event.dto;

import java.time.LocalDateTime;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateEventSessionRequest(
        @NotBlank String name,
        String venue,
        @NotNull LocalDateTime saleStartAt,
        @NotNull LocalDateTime saleEndAt,
        @NotNull LocalDateTime startAt,
        @NotNull LocalDateTime endAt) {
}

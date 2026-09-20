package com.eventhub.controller.event.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateEventRequest(
        @NotBlank String title,
        String description) {
}

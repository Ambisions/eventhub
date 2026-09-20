package com.eventhub.controller.event.dto;

import jakarta.validation.constraints.NotBlank;

public record UpdateEventRequest(
        @NotBlank String title,
        String description) {
}

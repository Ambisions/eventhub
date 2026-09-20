package com.eventhub.controller.event.dto;

import jakarta.validation.constraints.NotBlank;

public record RejectEventRequest(@NotBlank String reason) {
}

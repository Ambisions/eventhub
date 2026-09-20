package com.eventhub.controller.inventory.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record ConfigureInventoryRequest(@NotNull @Min(0) Integer totalStock) {
}

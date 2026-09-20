package com.eventhub.inventory.api.model;

import java.time.LocalDateTime;

public record InventoryView(
        long sessionId,
        int totalStock,
        int availableStock,
        int lockedStock,
        int soldStock,
        LocalDateTime updatedAt) {
}

package com.eventhub.controller.inventory.dto;

import java.time.LocalDateTime;

import com.eventhub.inventory.api.model.InventoryView;

public record InventoryResponse(
        long sessionId,
        int totalStock,
        int availableStock,
        int lockedStock,
        int soldStock,
        LocalDateTime updatedAt) {

    public static InventoryResponse from(InventoryView view) {
        return new InventoryResponse(
                view.sessionId(), view.totalStock(), view.availableStock(), view.lockedStock(),
                view.soldStock(), view.updatedAt());
    }
}

package com.eventhub.inventory.api.command;

public record FlashOrderLifecycleCommand(
        long sessionId,
        long userId,
        long orderId) {
}

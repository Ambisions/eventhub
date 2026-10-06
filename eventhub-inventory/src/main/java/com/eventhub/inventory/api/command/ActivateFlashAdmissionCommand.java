package com.eventhub.inventory.api.command;

public record ActivateFlashAdmissionCommand(
        long sessionId,
        long userId,
        String expectedAdmissionId,
        long orderId) {
}

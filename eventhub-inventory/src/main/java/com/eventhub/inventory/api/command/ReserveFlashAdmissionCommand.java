package com.eventhub.inventory.api.command;

public record ReserveFlashAdmissionCommand(
        long sessionId,
        long userId,
        String admissionId) {
}

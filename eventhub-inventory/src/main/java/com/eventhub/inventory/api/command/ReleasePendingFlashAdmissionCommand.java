package com.eventhub.inventory.api.command;

public record ReleasePendingFlashAdmissionCommand(
        long sessionId,
        long userId,
        String expectedAdmissionId) {
}

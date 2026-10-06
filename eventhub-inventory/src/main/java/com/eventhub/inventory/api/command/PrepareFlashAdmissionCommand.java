package com.eventhub.inventory.api.command;

public record PrepareFlashAdmissionCommand(
        long sessionId,
        int capacity,
        long saleStartUs,
        long saleEndUs) {
}

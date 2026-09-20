package com.eventhub.inventory.api.command;

public record ConfigureInventoryCommand(long sessionId, int totalStock) {
}

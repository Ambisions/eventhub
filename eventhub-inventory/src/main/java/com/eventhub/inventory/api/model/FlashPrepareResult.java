package com.eventhub.inventory.api.model;

public enum FlashPrepareResult {
    PREPARED,
    IDEMPOTENT_PREPARED,
    WINDOW_CLOSED,
    STATE_CONFLICT
}

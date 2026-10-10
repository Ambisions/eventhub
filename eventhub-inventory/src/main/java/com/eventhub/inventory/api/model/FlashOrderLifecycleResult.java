package com.eventhub.inventory.api.model;

public enum FlashOrderLifecycleResult {
    RELEASED,
    IDEMPOTENT_RELEASED,
    REFUNDED,
    IDEMPOTENT_REFUNDED,
    FENCE_CONFLICT,
    NOT_PREPARED,
    STATE_CONFLICT
}

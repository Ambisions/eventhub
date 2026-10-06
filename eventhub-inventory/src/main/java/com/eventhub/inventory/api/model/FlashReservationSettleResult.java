package com.eventhub.inventory.api.model;

public enum FlashReservationSettleResult {
    ACTIVATED,
    IDEMPOTENT_ACTIVATED,
    RELEASED,
    IDEMPOTENT_RELEASED,
    FENCE_CONFLICT,
    NOT_PREPARED,
    STATE_CONFLICT
}

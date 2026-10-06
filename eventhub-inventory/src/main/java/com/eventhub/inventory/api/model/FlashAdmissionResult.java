package com.eventhub.inventory.api.model;

public enum FlashAdmissionResult {
    ADMITTED,
    IDEMPOTENT_PENDING,
    ALREADY_ADMITTED,
    SOLD_OUT,
    NOT_STARTED,
    ENDED,
    NOT_PREPARED,
    STATE_CONFLICT
}

package com.eventhub.inventory.api.error;

public final class FlashAdmissionUnavailableException extends RuntimeException {

    public FlashAdmissionUnavailableException(String message) {
        super(message);
    }

    public FlashAdmissionUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}

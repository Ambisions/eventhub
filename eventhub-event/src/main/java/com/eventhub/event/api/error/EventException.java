package com.eventhub.event.api.error;

public final class EventException extends RuntimeException {

    private final EventErrorCode errorCode;

    public EventException(EventErrorCode errorCode) {
        super(errorCode.getDefaultMessage());
        this.errorCode = errorCode;
    }

    public EventErrorCode getErrorCode() {
        return errorCode;
    }
}

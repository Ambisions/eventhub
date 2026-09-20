package com.eventhub.event.api.error;

public enum EventErrorCode {
    EVENT_NOT_FOUND("EVENT_NOT_FOUND", "Event was not found"),
    SESSION_NOT_FOUND("SESSION_NOT_FOUND", "Event session was not found"),
    EVENT_NOT_EDITABLE("EVENT_NOT_EDITABLE", "Event is not editable in its current state"),
    ILLEGAL_EVENT_STATE("ILLEGAL_EVENT_STATE", "Event state transition is not allowed"),
    INVALID_EVENT("INVALID_EVENT", "Event data is invalid"),
    INVALID_SESSION("INVALID_SESSION", "Event session data is invalid"),
    INVALID_REJECTION_REASON("INVALID_REJECTION_REASON", "Rejection reason is invalid");

    private final String code;
    private final String defaultMessage;

    EventErrorCode(String code, String defaultMessage) {
        this.code = code;
        this.defaultMessage = defaultMessage;
    }

    public String getCode() {
        return code;
    }

    public String getDefaultMessage() {
        return defaultMessage;
    }
}

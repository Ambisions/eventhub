package com.eventhub.application.checkin.error;

public enum CheckInErrorCode {
    TICKET_NOT_FOUND("TICKET_NOT_FOUND", "Ticket not found"),
    TICKET_CHECK_IN_NOT_STARTED("TICKET_CHECK_IN_NOT_STARTED", "Ticket check-in has not started"),
    TICKET_CHECK_IN_CLOSED("TICKET_CHECK_IN_CLOSED", "Ticket check-in is closed"),
    TICKET_ALREADY_USED("TICKET_ALREADY_USED", "Ticket has already been used");

    private final String code;
    private final String defaultMessage;

    CheckInErrorCode(String code, String defaultMessage) {
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

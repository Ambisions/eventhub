package com.eventhub.ticket.api.error;

public enum TicketErrorCode {
    TICKET_NOT_FOUND("TICKET_NOT_FOUND", "Ticket not found"),
    TICKET_ALREADY_ISSUED("TICKET_ALREADY_ISSUED", "A ticket has already been issued"),
    TICKET_ISSUANCE_FAILED("TICKET_ISSUANCE_FAILED", "Ticket issuance failed");

    private final String code;
    private final String defaultMessage;

    TicketErrorCode(String code, String defaultMessage) {
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

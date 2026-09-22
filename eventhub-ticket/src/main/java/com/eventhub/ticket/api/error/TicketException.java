package com.eventhub.ticket.api.error;

public final class TicketException extends RuntimeException {

    private final TicketErrorCode errorCode;

    public TicketException(TicketErrorCode errorCode) {
        super(errorCode.getDefaultMessage());
        this.errorCode = errorCode;
    }

    public TicketException(TicketErrorCode errorCode, Throwable cause) {
        super(errorCode.getDefaultMessage(), cause);
        this.errorCode = errorCode;
    }

    public TicketErrorCode getErrorCode() {
        return errorCode;
    }
}

package com.eventhub.ticket.api.error;

public final class TicketCheckInException extends RuntimeException {

    private final TicketCheckInFailure failure;

    public TicketCheckInException(TicketCheckInFailure failure) {
        super(messageFor(failure));
        this.failure = failure;
    }

    public TicketCheckInFailure getFailure() {
        return failure;
    }

    private static String messageFor(TicketCheckInFailure failure) {
        return switch (failure) {
            case NOT_FOUND -> "Ticket not found";
            case ALREADY_USED -> "Ticket has already been used";
            case REFUNDED -> "Ticket has been refunded";
        };
    }
}

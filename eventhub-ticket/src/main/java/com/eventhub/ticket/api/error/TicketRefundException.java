package com.eventhub.ticket.api.error;

public final class TicketRefundException extends RuntimeException {

    private final TicketRefundFailure failure;

    public TicketRefundException(TicketRefundFailure failure) {
        super(messageFor(failure));
        this.failure = failure;
    }

    public TicketRefundFailure getFailure() {
        return failure;
    }

    private static String messageFor(TicketRefundFailure failure) {
        return switch (failure) {
            case USED -> "Ticket has already been used";
            case INCONSISTENT_STATE -> "Ticket state is inconsistent with refund processing";
        };
    }
}

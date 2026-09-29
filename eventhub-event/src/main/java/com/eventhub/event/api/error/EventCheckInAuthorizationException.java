package com.eventhub.event.api.error;

public final class EventCheckInAuthorizationException extends RuntimeException {

    private final EventCheckInAuthorizationFailure failure;

    public EventCheckInAuthorizationException(EventCheckInAuthorizationFailure failure) {
        super(messageFor(failure));
        this.failure = failure;
    }

    public EventCheckInAuthorizationFailure getFailure() {
        return failure;
    }

    private static String messageFor(EventCheckInAuthorizationFailure failure) {
        return switch (failure) {
            case NOT_FOUND_OR_NOT_OWNER -> "Event session was not found";
            case NOT_STARTED -> "Ticket check-in has not started";
            case CLOSED -> "Ticket check-in is closed";
        };
    }
}

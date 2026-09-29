package com.eventhub.application.checkin.error;

public final class CheckInException extends RuntimeException {

    private final CheckInErrorCode errorCode;

    public CheckInException(CheckInErrorCode errorCode, Throwable cause) {
        super(errorCode.getDefaultMessage(), cause);
        this.errorCode = errorCode;
    }

    public CheckInErrorCode getErrorCode() {
        return errorCode;
    }
}

package com.eventhub.application.flash.error;

public final class FlashException extends RuntimeException {

    private final FlashErrorCode errorCode;

    public FlashException(FlashErrorCode errorCode) {
        super(errorCode.getDefaultMessage());
        this.errorCode = errorCode;
    }

    public FlashException(FlashErrorCode errorCode, Throwable cause) {
        super(errorCode.getDefaultMessage(), cause);
        this.errorCode = errorCode;
    }

    public FlashErrorCode getErrorCode() {
        return errorCode;
    }
}

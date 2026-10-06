package com.eventhub.application.flash.error;

public final class FlashPreparationException extends RuntimeException {

    private final FlashPreparationErrorCode errorCode;

    public FlashPreparationException(FlashPreparationErrorCode errorCode) {
        super(errorCode.getDefaultMessage());
        this.errorCode = errorCode;
    }

    public FlashPreparationException(FlashPreparationErrorCode errorCode, Throwable cause) {
        super(errorCode.getDefaultMessage(), cause);
        this.errorCode = errorCode;
    }

    public FlashPreparationErrorCode getErrorCode() {
        return errorCode;
    }
}

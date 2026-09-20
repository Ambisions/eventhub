package com.eventhub.order.api.error;

public final class OrderException extends RuntimeException {

    private final OrderErrorCode errorCode;

    public OrderException(OrderErrorCode errorCode) {
        super(errorCode.getDefaultMessage());
        this.errorCode = errorCode;
    }

    public OrderException(OrderErrorCode errorCode, Throwable cause) {
        super(errorCode.getDefaultMessage(), cause);
        this.errorCode = errorCode;
    }

    public OrderErrorCode getErrorCode() {
        return errorCode;
    }
}

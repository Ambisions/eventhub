package com.eventhub.order.api.error;

public enum OrderErrorCode {
    ACTIVE_PURCHASE_EXISTS("ACTIVE_PURCHASE_EXISTS", "An active purchase already exists"),
    SALE_NOT_STARTED("SALE_NOT_STARTED", "The sale has not started"),
    SALE_ENDED("SALE_ENDED", "The sale has ended"),
    ORDER_NOT_FOUND("ORDER_NOT_FOUND", "Order not found"),
    ORDER_EXPIRED("ORDER_EXPIRED", "The order payment window has expired"),
    ORDER_STATE_CONFLICT("ORDER_STATE_CONFLICT", "The order state does not allow this operation");

    private final String code;
    private final String defaultMessage;

    OrderErrorCode(String code, String defaultMessage) {
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

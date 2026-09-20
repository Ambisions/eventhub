package com.eventhub.inventory.api.error;

public final class InventoryException extends RuntimeException {

    private final InventoryErrorCode errorCode;

    public InventoryException(InventoryErrorCode errorCode) {
        super(errorCode.getDefaultMessage());
        this.errorCode = errorCode;
    }

    public InventoryException(InventoryErrorCode errorCode, Throwable cause) {
        super(errorCode.getDefaultMessage(), cause);
        this.errorCode = errorCode;
    }

    public InventoryErrorCode getErrorCode() {
        return errorCode;
    }
}

package com.eventhub.inventory.api.error;

public enum InventoryErrorCode {
    INVALID_INVENTORY_TOTAL("INVALID_INVENTORY_TOTAL", "Inventory total is invalid"),
    INVENTORY_NOT_CONFIGURED("INVENTORY_NOT_CONFIGURED", "Inventory is not configured"),
    SOLD_OUT("SOLD_OUT", "Inventory is sold out"),
    INVENTORY_STATE_CONFLICT("INVENTORY_STATE_CONFLICT", "Inventory state does not allow this operation"),
    INVENTORY_ALREADY_IN_USE("INVENTORY_ALREADY_IN_USE", "Inventory capacity cannot change after use");

    private final String code;
    private final String defaultMessage;

    InventoryErrorCode(String code, String defaultMessage) {
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

package com.eventhub.application.flash.error;

public enum FlashPreparationErrorCode {
    FLASH_PREPARATION_NOT_FOUND("FLASH_PREPARATION_NOT_FOUND", "FLASH preparation resource was not found"),
    FLASH_PREPARATION_NOT_ALLOWED("FLASH_PREPARATION_NOT_ALLOWED", "FLASH preparation is not allowed"),
    FLASH_PREPARATION_WINDOW_CLOSED("FLASH_PREPARATION_WINDOW_CLOSED", "FLASH preparation window is closed"),
    FLASH_PREPARATION_STATE_CONFLICT("FLASH_PREPARATION_STATE_CONFLICT", "FLASH preparation state conflicts with the request"),
    FLASH_SERVICE_UNAVAILABLE("FLASH_SERVICE_UNAVAILABLE", "FLASH service is unavailable");

    private final String code;
    private final String defaultMessage;

    FlashPreparationErrorCode(String code, String defaultMessage) {
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

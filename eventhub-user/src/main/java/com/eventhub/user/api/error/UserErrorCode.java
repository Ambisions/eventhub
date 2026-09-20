package com.eventhub.user.api.error;

public enum UserErrorCode {
    USER_NOT_FOUND("USER_NOT_FOUND", "User was not found"),
    USERNAME_ALREADY_EXISTS("USERNAME_ALREADY_EXISTS", "Username already exists"),
    INVALID_USERNAME("INVALID_USERNAME", "Username is invalid"),
    INVALID_DISPLAY_NAME("INVALID_DISPLAY_NAME", "Display name is invalid"),
    INVALID_ENCODED_PASSWORD("INVALID_ENCODED_PASSWORD", "Encoded password is invalid");

    private final String code;

    private final String defaultMessage;

    UserErrorCode(String code, String defaultMessage) {
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

package com.eventhub.auth.api.error;

public enum AuthErrorCode {
    INVALID_PASSWORD("INVALID_PASSWORD", "Password does not satisfy the security policy"),
    INVALID_CREDENTIALS("INVALID_CREDENTIALS", "Invalid username or password");

    private final String code;

    private final String defaultMessage;

    AuthErrorCode(String code, String defaultMessage) {
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

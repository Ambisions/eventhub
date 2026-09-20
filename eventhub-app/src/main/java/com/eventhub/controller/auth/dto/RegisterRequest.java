package com.eventhub.controller.auth.dto;

import jakarta.validation.constraints.NotNull;

public final class RegisterRequest {

    @NotNull
    private String username;

    @NotNull
    private String password;

    private String displayName;

    public RegisterRequest() {
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    @Override
    public String toString() {
        return "RegisterRequest{" +
                "username='" + username + '\'' +
                ", password=[PROTECTED]" +
                ", displayName='" + displayName + '\'' +
                '}';
    }
}

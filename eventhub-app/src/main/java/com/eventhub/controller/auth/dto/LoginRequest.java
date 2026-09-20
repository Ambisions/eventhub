package com.eventhub.controller.auth.dto;

import jakarta.validation.constraints.NotNull;

public final class LoginRequest {

    @NotNull
    private String username;

    @NotNull
    private String password;

    public LoginRequest() {
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

    @Override
    public String toString() {
        return "LoginRequest{" +
                "username='" + username + '\'' +
                ", password=[PROTECTED]" +
                '}';
    }
}

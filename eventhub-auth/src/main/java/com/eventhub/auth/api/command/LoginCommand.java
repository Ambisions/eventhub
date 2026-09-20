package com.eventhub.auth.api.command;

public final class LoginCommand {

    private final String username;

    private final String rawPassword;

    public LoginCommand(String username, String rawPassword) {
        this.username = username;
        this.rawPassword = rawPassword;
    }

    public String getUsername() {
        return username;
    }

    public String getRawPassword() {
        return rawPassword;
    }

    @Override
    public String toString() {
        return "LoginCommand{" +
                "username='" + username + '\'' +
                ", rawPassword=[PROTECTED]" +
                '}';
    }
}

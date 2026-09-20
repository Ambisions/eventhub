package com.eventhub.auth.api.command;

public final class RegisterCommand {

    private final String username;

    private final String rawPassword;

    private final String displayName;

    public RegisterCommand(String username, String rawPassword, String displayName) {
        this.username = username;
        this.rawPassword = rawPassword;
        this.displayName = displayName;
    }

    public String getUsername() {
        return username;
    }

    public String getRawPassword() {
        return rawPassword;
    }

    public String getDisplayName() {
        return displayName;
    }

    @Override
    public String toString() {
        return "RegisterCommand{" +
                "username='" + username + '\'' +
                ", rawPassword=[PROTECTED]" +
                ", displayName='" + displayName + '\'' +
                '}';
    }
}

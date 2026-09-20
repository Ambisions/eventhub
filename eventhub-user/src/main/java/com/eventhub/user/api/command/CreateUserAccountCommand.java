package com.eventhub.user.api.command;

public final class CreateUserAccountCommand {

    private final String username;

    private final String encodedPassword;

    private final String displayName;

    public CreateUserAccountCommand(String username, String encodedPassword, String displayName) {
        this.username = username;
        this.encodedPassword = encodedPassword;
        this.displayName = displayName;
    }

    public String getUsername() {
        return username;
    }

    public String getEncodedPassword() {
        return encodedPassword;
    }

    public String getDisplayName() {
        return displayName;
    }

    @Override
    public String toString() {
        return "CreateUserAccountCommand{" +
                "username='" + username + '\'' +
                ", encodedPassword=[PROTECTED]" +
                ", displayName='" + displayName + '\'' +
                '}';
    }
}

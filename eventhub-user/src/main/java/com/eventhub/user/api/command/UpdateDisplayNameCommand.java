package com.eventhub.user.api.command;

public final class UpdateDisplayNameCommand {

    private final String displayName;

    public UpdateDisplayNameCommand(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}

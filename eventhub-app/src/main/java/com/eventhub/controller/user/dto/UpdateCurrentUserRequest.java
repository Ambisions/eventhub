package com.eventhub.controller.user.dto;

public final class UpdateCurrentUserRequest {

    private String displayName;

    public UpdateCurrentUserRequest() {
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }
}

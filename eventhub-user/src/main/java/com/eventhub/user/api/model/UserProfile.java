package com.eventhub.user.api.model;

import java.time.LocalDateTime;

public final class UserProfile {

    private final Long id;

    private final String username;

    private final String displayName;

    private final UserRole role;

    private final LocalDateTime createdAt;

    private final LocalDateTime updatedAt;

    public UserProfile(
            Long id,
            String username,
            String displayName,
            UserRole role,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {
        this.id = id;
        this.username = username;
        this.displayName = displayName;
        this.role = role;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public Long getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getDisplayName() {
        return displayName;
    }

    public UserRole getRole() {
        return role;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}

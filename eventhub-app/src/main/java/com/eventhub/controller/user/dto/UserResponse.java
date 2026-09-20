package com.eventhub.controller.user.dto;

import java.time.LocalDateTime;

import com.eventhub.user.api.model.UserProfile;
import com.eventhub.user.api.model.UserRole;

public record UserResponse(
        Long id,
        String username,
        String displayName,
        UserRole role,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public static UserResponse from(UserProfile profile) {
        return new UserResponse(
                profile.getId(),
                profile.getUsername(),
                profile.getDisplayName(),
                profile.getRole(),
                profile.getCreatedAt(),
                profile.getUpdatedAt());
    }
}

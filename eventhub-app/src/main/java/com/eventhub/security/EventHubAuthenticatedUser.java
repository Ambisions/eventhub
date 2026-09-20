package com.eventhub.security;

import com.eventhub.user.api.model.UserRole;

public record EventHubAuthenticatedUser(long userId, String username, UserRole role) {
}

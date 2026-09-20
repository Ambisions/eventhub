package com.eventhub.user.api.model;

public final class UserAuthenticationData {

    private final Long userId;

    private final String username;

    private final String encodedPassword;

    private final UserRole role;

    public UserAuthenticationData(Long userId, String username, String encodedPassword, UserRole role) {
        this.userId = userId;
        this.username = username;
        this.encodedPassword = encodedPassword;
        this.role = role;
    }

    public Long getUserId() {
        return userId;
    }

    public String getUsername() {
        return username;
    }

    public String getEncodedPassword() {
        return encodedPassword;
    }

    public UserRole getRole() {
        return role;
    }

    @Override
    public String toString() {
        return "UserAuthenticationData{" +
                "userId=" + userId +
                ", username='" + username + '\'' +
                ", encodedPassword=[PROTECTED]" +
                ", role=" + role +
                '}';
    }
}

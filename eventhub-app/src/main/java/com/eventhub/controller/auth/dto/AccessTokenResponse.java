package com.eventhub.controller.auth.dto;

import java.time.Instant;

import com.eventhub.auth.api.model.AccessToken;

public final class AccessTokenResponse {

    private final String accessToken;

    private final String tokenType;

    private final Instant expiresAt;

    private AccessTokenResponse(String accessToken, String tokenType, Instant expiresAt) {
        this.accessToken = accessToken;
        this.tokenType = tokenType;
        this.expiresAt = expiresAt;
    }

    public static AccessTokenResponse from(AccessToken accessToken) {
        return new AccessTokenResponse(accessToken.getTokenValue(), "Bearer", accessToken.getExpiresAt());
    }

    public String getAccessToken() {
        return accessToken;
    }

    public String getTokenType() {
        return tokenType;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    @Override
    public String toString() {
        return "AccessTokenResponse{" +
                "accessToken=[PROTECTED]" +
                ", tokenType='" + tokenType + '\'' +
                ", expiresAt=" + expiresAt +
                '}';
    }
}

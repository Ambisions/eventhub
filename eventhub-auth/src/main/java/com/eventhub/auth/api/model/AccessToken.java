package com.eventhub.auth.api.model;

import java.time.Instant;

public final class AccessToken {

    private final String tokenValue;

    private final Instant expiresAt;

    public AccessToken(String tokenValue, Instant expiresAt) {
        this.tokenValue = tokenValue;
        this.expiresAt = expiresAt;
    }

    public String getTokenValue() {
        return tokenValue;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    @Override
    public String toString() {
        return "AccessToken{" +
                "tokenValue=[PROTECTED]" +
                ", expiresAt=" + expiresAt +
                '}';
    }
}

package com.eventhub.auth.api.model;

import java.time.Duration;

public final class AccessTokenContract {

    public static final String ISSUER = "eventhub";

    public static final Duration TIME_TO_LIVE = Duration.ofMinutes(30);

    public static final String USERNAME_CLAIM = "username";

    public static final String ROLE_CLAIM = "role";

    private AccessTokenContract() {
    }
}

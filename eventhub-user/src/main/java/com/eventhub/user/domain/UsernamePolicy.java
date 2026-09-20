package com.eventhub.user.domain;

import java.util.Locale;
import java.util.regex.Pattern;

import com.eventhub.user.api.error.UserErrorCode;
import com.eventhub.user.api.error.UserException;

public final class UsernamePolicy {

    private static final Pattern CANONICAL_USERNAME = Pattern.compile("^[a-z0-9._-]{3,64}$");

    private UsernamePolicy() {
    }

    public static String canonicalize(String username) {
        if (username == null) {
            throw new UserException(UserErrorCode.INVALID_USERNAME);
        }

        String canonicalUsername = username.trim().toLowerCase(Locale.ROOT);
        if (!CANONICAL_USERNAME.matcher(canonicalUsername).matches()) {
            throw new UserException(UserErrorCode.INVALID_USERNAME);
        }
        return canonicalUsername;
    }
}

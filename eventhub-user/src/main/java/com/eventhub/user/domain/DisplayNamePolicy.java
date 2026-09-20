package com.eventhub.user.domain;

import com.eventhub.user.api.error.UserErrorCode;
import com.eventhub.user.api.error.UserException;

public final class DisplayNamePolicy {

    private static final int MAX_LENGTH = 100;

    private DisplayNamePolicy() {
    }

    public static String normalize(String displayName) {
        if (displayName == null) {
            return null;
        }

        String normalizedDisplayName = displayName.trim();
        int characterCount = normalizedDisplayName.codePointCount(0, normalizedDisplayName.length());
        if (characterCount == 0 || characterCount > MAX_LENGTH) {
            throw new UserException(UserErrorCode.INVALID_DISPLAY_NAME);
        }
        return normalizedDisplayName;
    }
}

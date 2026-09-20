package com.eventhub.user.domain;

import java.util.Locale;

import com.eventhub.user.api.error.UserErrorCode;
import com.eventhub.user.api.error.UserException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UsernamePolicyTest {

    @Test
    void canonicalizesTrimmedUppercaseUsernameWithLocaleRoot() {
        Locale originalLocale = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertEquals("i.user", UsernamePolicy.canonicalize("  I.USER  "));
        } finally {
            Locale.setDefault(originalLocale);
        }
    }

    @Test
    void acceptsThreeAndSixtyFourCharacterBoundaries() {
        assertEquals("a-1", UsernamePolicy.canonicalize("a-1"));
        String maximumLengthUsername = "a".repeat(64);
        assertEquals(maximumLengthUsername, UsernamePolicy.canonicalize(maximumLengthUsername));
    }

    @Test
    void rejectsInvalidUsernameInputs() {
        assertInvalid(null);
        assertInvalid("   ");
        assertInvalid("ab");
        assertInvalid("a".repeat(65));
        assertInvalid("user@example");
        assertInvalid("user name");
    }

    private static void assertInvalid(String username) {
        UserException exception = assertThrows(
                UserException.class,
                () -> UsernamePolicy.canonicalize(username));
        assertEquals(UserErrorCode.INVALID_USERNAME, exception.getErrorCode());
    }
}

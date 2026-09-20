package com.eventhub.user.domain;

import com.eventhub.user.api.error.UserErrorCode;
import com.eventhub.user.api.error.UserException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DisplayNamePolicyTest {

    @Test
    void acceptsNullAndTrimsValidDisplayNames() {
        assertNull(DisplayNamePolicy.normalize(null));
        assertEquals("A", DisplayNamePolicy.normalize(" A "));
        assertEquals("A".repeat(100), DisplayNamePolicy.normalize("A".repeat(100)));
    }

    @Test
    void countsSupplementaryUnicodeCharactersAsSingleCharacters() {
        String oneHundredEmoji = "😃".repeat(100);
        assertEquals(oneHundredEmoji, DisplayNamePolicy.normalize(oneHundredEmoji));
    }

    @Test
    void rejectsBlankAndOverlongDisplayNames() {
        assertInvalid("");
        assertInvalid("   ");
        assertInvalid("A".repeat(101));
    }

    private static void assertInvalid(String displayName) {
        UserException exception = assertThrows(
                UserException.class,
                () -> DisplayNamePolicy.normalize(displayName));
        assertEquals(UserErrorCode.INVALID_DISPLAY_NAME, exception.getErrorCode());
    }
}

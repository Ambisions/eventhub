package com.eventhub.auth.domain;

import com.eventhub.auth.api.error.AuthErrorCode;
import com.eventhub.auth.api.error.AuthException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PasswordPolicyTest {

    @Test
    void acceptsEightAndSixtyFourUnicodeCodePoints() {
        assertTrue(PasswordPolicy.isValid("a".repeat(8)));
        assertTrue(PasswordPolicy.isValid("a".repeat(64)));
        assertDoesNotThrow(() -> PasswordPolicy.validate("a".repeat(8)));
    }

    @Test
    void rejectsNullAndCodePointCountsOutsideFrozenRange() {
        assertFalse(PasswordPolicy.isValid(null));
        assertFalse(PasswordPolicy.isValid("a".repeat(7)));
        assertFalse(PasswordPolicy.isValid("a".repeat(65)));

        AuthException exception = assertThrows(AuthException.class, () -> PasswordPolicy.validate(null));
        assertEquals(AuthErrorCode.INVALID_PASSWORD, exception.getErrorCode());
    }

    @Test
    void countsSupplementaryCharactersAsSingleCodePoints() {
        String eightCodePoints = "😀".repeat(8);

        assertEquals(16, eightCodePoints.length());
        assertEquals(8, eightCodePoints.codePointCount(0, eightCodePoints.length()));
        assertTrue(PasswordPolicy.isValid(eightCodePoints));
    }

    @Test
    void enforcesExactBcryptUtf8ByteBoundary() {
        String seventyTwoBytes = "界".repeat(24);
        String seventyThreeBytes = seventyTwoBytes + "a";

        assertEquals(72, seventyTwoBytes.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        assertEquals(73, seventyThreeBytes.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        assertTrue(PasswordPolicy.isValid(seventyTwoBytes));
        assertFalse(PasswordPolicy.isValid(seventyThreeBytes));
    }

    @Test
    void treatsPasswordAsOpaqueWithoutTrimming() {
        assertTrue(PasswordPolicy.isValid("       a"));
        assertTrue(PasswordPolicy.isValid("a       "));
    }

    @Test
    void rejectsMalformedUtf16InsteadOfSilentlyReplacingIt() {
        String malformed = "\uD800" + "a".repeat(7);

        assertEquals(8, malformed.codePointCount(0, malformed.length()));
        assertFalse(PasswordPolicy.isValid(malformed));
    }
}

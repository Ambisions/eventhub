package com.eventhub.auth.domain;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PasswordEncoderTest {

    @Test
    void delegatesNewPasswordsToBcryptAndMatchesOnlyTheExactOpaqueValue() {
        PasswordEncoder encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
        String rawPassword = " ÅPassword ";

        String encoded = encoder.encode(rawPassword);

        assertTrue(encoded.startsWith("{bcrypt}"));
        assertTrue(encoder.matches(rawPassword, encoded));
        assertFalse(encoder.matches(rawPassword.trim(), encoded));
        assertFalse(encoder.matches(" ÅPassword ", encoded));
        assertFalse(encoder.matches("wrong-password", encoded));
    }
}

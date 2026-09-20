package com.eventhub.auth.api;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import com.eventhub.auth.api.command.LoginCommand;
import com.eventhub.auth.api.command.RegisterCommand;
import com.eventhub.auth.api.model.AccessToken;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SensitiveAuthApiModelTest {

    @Test
    void registerCommandCannotSpecifyRoleOrEncodedCredential() {
        Set<String> fieldNames = fieldNames(RegisterCommand.class);

        assertFalse(fieldNames.contains("role"));
        assertFalse(fieldNames.contains("passwordhash"));
        assertFalse(fieldNames.contains("encodedpassword"));
        assertTrue(fieldNames.contains("rawpassword"));
    }

    @Test
    void sensitiveModelsRedactPasswordsAndTokensFromStringOutput() {
        String password = "highly-sensitive-password";
        String tokenValue = "signed.jwt.token";
        RegisterCommand register = new RegisterCommand("alice", password, null);
        LoginCommand login = new LoginCommand("alice", password);
        AccessToken token = new AccessToken(tokenValue, Instant.parse("2030-01-01T00:00:00Z"));

        assertFalse(register.toString().contains(password));
        assertFalse(login.toString().contains(password));
        assertFalse(token.toString().contains(tokenValue));
        assertTrue(register.toString().contains("[PROTECTED]"));
        assertTrue(login.toString().contains("[PROTECTED]"));
        assertTrue(token.toString().contains("[PROTECTED]"));
    }

    private static Set<String> fieldNames(Class<?> type) {
        return Arrays.stream(type.getDeclaredFields())
                .map(Field::getName)
                .map(name -> name.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
    }
}

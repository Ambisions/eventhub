package com.eventhub.user.api;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import com.eventhub.user.api.command.CreateUserAccountCommand;
import com.eventhub.user.api.model.UserAuthenticationData;
import com.eventhub.user.api.model.UserProfile;
import com.eventhub.user.api.model.UserRole;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SensitiveApiModelTest {

    @Test
    void profileDoesNotContainCredentialFields() {
        Set<String> fieldNames = fieldNames(UserProfile.class);

        assertFalse(fieldNames.contains("password"));
        assertFalse(fieldNames.contains("passwordhash"));
        assertFalse(fieldNames.contains("encodedpassword"));
    }

    @Test
    void createAccountCommandCannotSpecifyRoleOrRawPassword() {
        Set<String> fieldNames = fieldNames(CreateUserAccountCommand.class);

        assertFalse(fieldNames.contains("role"));
        assertFalse(fieldNames.contains("rawpassword"));
        assertTrue(fieldNames.contains("encodedpassword"));
    }

    @Test
    void sensitiveModelsRedactEncodedCredentialInToString() {
        String credential = "encoded-secret-value";
        CreateUserAccountCommand command = new CreateUserAccountCommand("alice", credential, null);
        UserAuthenticationData authenticationData =
                new UserAuthenticationData(1L, "alice", credential, UserRole.USER);

        assertFalse(command.toString().contains(credential));
        assertFalse(authenticationData.toString().contains(credential));
        assertTrue(command.toString().contains("[PROTECTED]"));
        assertTrue(authenticationData.toString().contains("[PROTECTED]"));
    }

    private static Set<String> fieldNames(Class<?> type) {
        return Arrays.stream(type.getDeclaredFields())
                .map(Field::getName)
                .map(name -> name.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
    }
}

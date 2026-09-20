package com.eventhub.user.service;

import java.sql.SQLIntegrityConstraintViolationException;

import com.eventhub.user.api.command.CreateUserAccountCommand;
import com.eventhub.user.api.error.UserErrorCode;
import com.eventhub.user.api.error.UserException;
import com.eventhub.user.mapper.UserMapper;
import com.eventhub.user.repository.UserEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserMapper userMapper;

    private UserService userService;

    @BeforeEach
    void setUp() {
        userService = new UserService(userMapper);
    }

    @Test
    void translatesOnlyUsernameUniqueConstraintViolations() {
        DuplicateKeyException duplicate = duplicateKey("users.uk_users_username");
        when(userMapper.insert(any(UserEntity.class))).thenThrow(duplicate);

        UserException exception = assertThrows(
                UserException.class,
                () -> userService.createAccount(validCommand()));

        assertEquals(UserErrorCode.USERNAME_ALREADY_EXISTS, exception.getErrorCode());
        assertSame(duplicate, exception.getCause());
    }

    @Test
    void doesNotMisclassifyOtherDuplicateConstraintsAsUsernameConflicts() {
        DuplicateKeyException duplicate = duplicateKey("users.PRIMARY");
        when(userMapper.insert(any(UserEntity.class))).thenThrow(duplicate);

        DuplicateKeyException exception = assertThrows(
                DuplicateKeyException.class,
                () -> userService.createAccount(validCommand()));

        assertSame(duplicate, exception);
    }

    @Test
    void rejectsInvalidEncodedPasswordWithoutInvokingPersistence() {
        UserException exception = assertThrows(
                UserException.class,
                () -> userService.createAccount(
                        new CreateUserAccountCommand("alice", " ", null)));

        assertEquals(UserErrorCode.INVALID_ENCODED_PASSWORD, exception.getErrorCode());

        UserException missingPassword = assertThrows(
                UserException.class,
                () -> userService.createAccount(
                        new CreateUserAccountCommand("alice", null, null)));
        assertEquals(UserErrorCode.INVALID_ENCODED_PASSWORD, missingPassword.getErrorCode());

        UserException overlongPassword = assertThrows(
                UserException.class,
                () -> userService.createAccount(
                        new CreateUserAccountCommand("alice", "x".repeat(256), null)));
        assertEquals(UserErrorCode.INVALID_ENCODED_PASSWORD, overlongPassword.getErrorCode());
    }

    private static CreateUserAccountCommand validCommand() {
        return new CreateUserAccountCommand("Alice", "encoded-password", null);
    }

    private static DuplicateKeyException duplicateKey(String constraintName) {
        return new DuplicateKeyException(
                "Duplicate key",
                new SQLIntegrityConstraintViolationException(
                        "Duplicate entry for key '" + constraintName + "'",
                        "23000",
                        1062));
    }
}

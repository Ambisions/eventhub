package com.eventhub.auth.service;

import java.time.Instant;
import java.util.Optional;

import com.eventhub.auth.api.command.LoginCommand;
import com.eventhub.auth.api.command.RegisterCommand;
import com.eventhub.auth.api.error.AuthErrorCode;
import com.eventhub.auth.api.error.AuthException;
import com.eventhub.auth.api.model.AccessToken;
import com.eventhub.user.api.UserAccountApi;
import com.eventhub.user.api.UserCredentialQueryApi;
import com.eventhub.user.api.command.CreateUserAccountCommand;
import com.eventhub.user.api.error.UserErrorCode;
import com.eventhub.user.api.error.UserException;
import com.eventhub.user.api.model.UserAuthenticationData;
import com.eventhub.user.api.model.UserProfile;
import com.eventhub.user.api.model.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    private static final String DUMMY_RAW_PASSWORD = "eventhub-dummy-password";

    private static final String DUMMY_HASH = "{bcrypt}dummy";

    @Mock
    private UserAccountApi userAccountApi;

    @Mock
    private UserCredentialQueryApi userCredentialQueryApi;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private AccessTokenIssuer accessTokenIssuer;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        when(passwordEncoder.encode(DUMMY_RAW_PASSWORD)).thenReturn(DUMMY_HASH);
        authService = new AuthService(
                userAccountApi,
                userCredentialQueryApi,
                passwordEncoder,
                accessTokenIssuer);
    }

    @Test
    void registersByEncodingRawPasswordAndDelegatingUsernameRulesToUserModule() {
        String rawPassword = "valid-password";
        UserProfile profile = new UserProfile(1L, "alice", null, UserRole.USER, null, null);
        when(passwordEncoder.encode(rawPassword)).thenReturn("{bcrypt}encoded");
        when(userAccountApi.createAccount(org.mockito.ArgumentMatchers.any())).thenReturn(profile);

        UserProfile result = authService.register(new RegisterCommand("  ALICE  ", rawPassword, null));

        assertSame(profile, result);
        ArgumentCaptor<CreateUserAccountCommand> command = ArgumentCaptor.forClass(CreateUserAccountCommand.class);
        verify(userAccountApi).createAccount(command.capture());
        assertEquals("  ALICE  ", command.getValue().getUsername());
        assertEquals("{bcrypt}encoded", command.getValue().getEncodedPassword());
    }

    @Test
    void issuesTokenAfterExactlyOneSuccessfulPasswordMatch() {
        String rawPassword = "correct-password";
        UserAuthenticationData credential = credential();
        AccessToken token = new AccessToken("token", Instant.parse("2030-01-01T00:00:00Z"));
        when(userCredentialQueryApi.findByUsername(" ALICE ")).thenReturn(Optional.of(credential));
        when(passwordEncoder.matches(rawPassword, credential.getEncodedPassword())).thenReturn(true);
        when(accessTokenIssuer.issue(credential)).thenReturn(token);

        AccessToken result = authService.login(new LoginCommand(" ALICE ", rawPassword));

        assertSame(token, result);
        verify(passwordEncoder).matches(rawPassword, credential.getEncodedPassword());
        verify(accessTokenIssuer).issue(credential);
    }

    @Test
    void masksUnknownUsernameAndPerformsOneDummyMatch() {
        when(userCredentialQueryApi.findByUsername("missing-user")).thenReturn(Optional.empty());
        when(passwordEncoder.matches("correct-password", DUMMY_HASH)).thenReturn(false);

        AuthException exception = assertThrows(
                AuthException.class,
                () -> authService.login(new LoginCommand("missing-user", "correct-password")));

        assertEquals(AuthErrorCode.INVALID_CREDENTIALS, exception.getErrorCode());
        verify(passwordEncoder).matches("correct-password", DUMMY_HASH);
        verify(accessTokenIssuer, never()).issue(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void masksInvalidUsernameAndPerformsOneDummyMatch() {
        when(userCredentialQueryApi.findByUsername("!"))
                .thenThrow(new UserException(UserErrorCode.INVALID_USERNAME));
        when(passwordEncoder.matches("correct-password", DUMMY_HASH)).thenReturn(false);

        AuthException exception = assertThrows(
                AuthException.class,
                () -> authService.login(new LoginCommand("!", "correct-password")));

        assertEquals(AuthErrorCode.INVALID_CREDENTIALS, exception.getErrorCode());
        verify(passwordEncoder).matches("correct-password", DUMMY_HASH);
    }

    @Test
    void masksWrongPasswordAfterExactlyOneRealHashMatch() {
        UserAuthenticationData credential = credential();
        when(userCredentialQueryApi.findByUsername("alice")).thenReturn(Optional.of(credential));
        when(passwordEncoder.matches("wrong-password", credential.getEncodedPassword())).thenReturn(false);

        AuthException exception = assertThrows(
                AuthException.class,
                () -> authService.login(new LoginCommand("alice", "wrong-password")));

        assertEquals(AuthErrorCode.INVALID_CREDENTIALS, exception.getErrorCode());
        verify(passwordEncoder).matches("wrong-password", credential.getEncodedPassword());
        verify(accessTokenIssuer, never()).issue(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void invalidPasswordShapeStillPerformsOnlyOneSafeDummyMatch() {
        UserAuthenticationData credential = credential();
        when(userCredentialQueryApi.findByUsername("alice")).thenReturn(Optional.of(credential));
        when(passwordEncoder.matches(DUMMY_RAW_PASSWORD, DUMMY_HASH)).thenReturn(true);

        AuthException exception = assertThrows(
                AuthException.class,
                () -> authService.login(new LoginCommand("alice", "short")));

        assertEquals(AuthErrorCode.INVALID_CREDENTIALS, exception.getErrorCode());
        verify(passwordEncoder).matches(DUMMY_RAW_PASSWORD, DUMMY_HASH);
        verify(accessTokenIssuer, never()).issue(org.mockito.ArgumentMatchers.any());
    }

    private static UserAuthenticationData credential() {
        return new UserAuthenticationData(1L, "alice", "{bcrypt}real", UserRole.USER);
    }
}

package com.eventhub.auth.service;

import java.util.Objects;
import java.util.Optional;

import com.eventhub.auth.api.AuthApi;
import com.eventhub.auth.api.command.LoginCommand;
import com.eventhub.auth.api.command.RegisterCommand;
import com.eventhub.auth.api.error.AuthErrorCode;
import com.eventhub.auth.api.error.AuthException;
import com.eventhub.auth.api.model.AccessToken;
import com.eventhub.auth.domain.PasswordPolicy;
import com.eventhub.user.api.UserAccountApi;
import com.eventhub.user.api.UserCredentialQueryApi;
import com.eventhub.user.api.command.CreateUserAccountCommand;
import com.eventhub.user.api.error.UserErrorCode;
import com.eventhub.user.api.error.UserException;
import com.eventhub.user.api.model.UserAuthenticationData;
import com.eventhub.user.api.model.UserProfile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public final class AuthService implements AuthApi {

    private static final String DUMMY_RAW_PASSWORD = "eventhub-dummy-password";

    private final UserAccountApi userAccountApi;

    private final UserCredentialQueryApi userCredentialQueryApi;

    private final PasswordEncoder passwordEncoder;

    private final AccessTokenIssuer accessTokenIssuer;

    private final String dummyEncodedPassword;

    public AuthService(
            UserAccountApi userAccountApi,
            UserCredentialQueryApi userCredentialQueryApi,
            PasswordEncoder passwordEncoder,
            AccessTokenIssuer accessTokenIssuer) {
        this.userAccountApi = userAccountApi;
        this.userCredentialQueryApi = userCredentialQueryApi;
        this.passwordEncoder = passwordEncoder;
        this.accessTokenIssuer = accessTokenIssuer;
        this.dummyEncodedPassword = passwordEncoder.encode(DUMMY_RAW_PASSWORD);
    }

    @Override
    public UserProfile register(RegisterCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        PasswordPolicy.validate(command.getRawPassword());
        String encodedPassword = passwordEncoder.encode(command.getRawPassword());
        return userAccountApi.createAccount(new CreateUserAccountCommand(
                command.getUsername(),
                encodedPassword,
                command.getDisplayName()));
    }

    @Override
    public AccessToken login(LoginCommand command) {
        if (command == null) {
            throw new AuthException(AuthErrorCode.INVALID_CREDENTIALS);
        }

        Optional<UserAuthenticationData> credential = findCredentialWithoutEnumeration(command.getUsername());
        boolean validPasswordShape = PasswordPolicy.isValid(command.getRawPassword());
        String candidatePassword = validPasswordShape ? command.getRawPassword() : DUMMY_RAW_PASSWORD;
        String encodedPassword = credential.isPresent() && validPasswordShape
                ? credential.orElseThrow().getEncodedPassword()
                : dummyEncodedPassword;
        boolean passwordMatches = passwordEncoder.matches(candidatePassword, encodedPassword);

        if (!validPasswordShape || credential.isEmpty() || !passwordMatches) {
            throw new AuthException(AuthErrorCode.INVALID_CREDENTIALS);
        }
        return accessTokenIssuer.issue(credential.orElseThrow());
    }

    private Optional<UserAuthenticationData> findCredentialWithoutEnumeration(String username) {
        try {
            return userCredentialQueryApi.findByUsername(username);
        } catch (UserException exception) {
            if (exception.getErrorCode() == UserErrorCode.INVALID_USERNAME) {
                return Optional.empty();
            }
            throw exception;
        }
    }
}

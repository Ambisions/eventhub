package com.eventhub.auth;

import java.util.Locale;
import java.util.UUID;

import com.eventhub.auth.api.AuthApi;
import com.eventhub.auth.api.command.LoginCommand;
import com.eventhub.auth.api.command.RegisterCommand;
import com.eventhub.auth.api.error.AuthErrorCode;
import com.eventhub.auth.api.error.AuthException;
import com.eventhub.auth.api.model.AccessToken;
import com.eventhub.user.api.UserCredentialQueryApi;
import com.eventhub.user.api.error.UserErrorCode;
import com.eventhub.user.api.error.UserException;
import com.eventhub.user.api.model.UserAuthenticationData;
import com.eventhub.user.api.model.UserProfile;
import com.eventhub.user.api.model.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.transaction.AfterTransaction;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AuthIntegrationTest {

    private static final String TEST_PREFIX = "phase5-auth-";

    @Autowired
    private AuthApi authApi;

    @Autowired
    private UserCredentialQueryApi userCredentialQueryApi;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtDecoder jwtDecoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void registersCanonicalUserWithBcryptAndIssuesFrozenJwtOnLogin() {
        String username = uniqueUsername("register");
        String rawPassword = "Exact Password 42";

        UserProfile profile = authApi.register(new RegisterCommand(
                "  " + username.toUpperCase(Locale.ROOT) + "  ",
                rawPassword,
                "  Alice Auth  "));

        assertThat(profile.getUsername()).isEqualTo(username);
        assertThat(profile.getDisplayName()).isEqualTo("Alice Auth");
        assertThat(profile.getRole()).isEqualTo(UserRole.USER);

        UserAuthenticationData credential = userCredentialQueryApi.findByUsername(username).orElseThrow();
        assertThat(credential.getEncodedPassword()).startsWith("{bcrypt}");
        assertThat(credential.getEncodedPassword()).doesNotContain(rawPassword);
        assertThat(passwordEncoder.matches(rawPassword, credential.getEncodedPassword())).isTrue();
        assertThat(databasePassword(profile.getId())).isEqualTo(credential.getEncodedPassword());

        AccessToken accessToken = authApi.login(new LoginCommand(
                "  " + username.toUpperCase(Locale.ROOT) + "  ",
                rawPassword));
        Jwt jwt = jwtDecoder.decode(accessToken.getTokenValue());
        assertThat(jwt.getSubject()).isEqualTo(profile.getId().toString());
        assertThat(jwt.getClaimAsString("username")).isEqualTo(username);
        assertThat(jwt.getClaimAsString("role")).isEqualTo("USER");
        assertThat(jwt.getClaimAsString("iss")).isEqualTo("eventhub");
        assertThat(java.time.Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt()))
                .isEqualTo(java.time.Duration.ofMinutes(30));
        assertThat(accessToken.toString()).doesNotContain(accessToken.getTokenValue());
    }

    @Test
    void keepsDatabaseUniqueConstraintAsCanonicalDuplicateSafetyLine() {
        String username = uniqueUsername("duplicate");
        authApi.register(new RegisterCommand(username.toUpperCase(Locale.ROOT), "valid-password", null));

        assertThatThrownBy(() -> authApi.register(new RegisterCommand(
                "  " + username + "  ",
                "another-valid-password",
                null)))
                .isInstanceOfSatisfying(UserException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(UserErrorCode.USERNAME_ALREADY_EXISTS));
    }

    @Test
    void exposesTheSameAuthenticationFailureForUnknownWrongAndInvalidUsernames() {
        String username = uniqueUsername("login");
        authApi.register(new RegisterCommand(username, "correct-password", null));

        assertInvalidCredentials(new LoginCommand(uniqueUsername("missing"), "correct-password"));
        assertInvalidCredentials(new LoginCommand(username, "wrong-password"));
        assertInvalidCredentials(new LoginCommand("!", "correct-password"));
    }

    @AfterTransaction
    void verifiesTransactionRollbackRemovedSyntheticUsers() {
        Long residueCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE username LIKE ?",
                Long.class,
                TEST_PREFIX + "%");
        assertThat(residueCount).isZero();
    }

    private void assertInvalidCredentials(LoginCommand command) {
        assertThatThrownBy(() -> authApi.login(command))
                .isInstanceOfSatisfying(AuthException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(AuthErrorCode.INVALID_CREDENTIALS));
    }

    private String databasePassword(long userId) {
        return jdbcTemplate.queryForObject(
                "SELECT password_hash FROM users WHERE id = ?",
                String.class,
                userId);
    }

    private static String uniqueUsername(String scenario) {
        return TEST_PREFIX + scenario + '-' + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}

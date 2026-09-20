package com.eventhub.user;

import java.util.Locale;
import java.util.UUID;

import com.eventhub.user.api.UserAccountApi;
import com.eventhub.user.api.UserCredentialQueryApi;
import com.eventhub.user.api.command.CreateUserAccountCommand;
import com.eventhub.user.api.command.UpdateDisplayNameCommand;
import com.eventhub.user.api.error.UserErrorCode;
import com.eventhub.user.api.error.UserException;
import com.eventhub.user.api.model.UserAuthenticationData;
import com.eventhub.user.api.model.UserProfile;
import com.eventhub.user.api.model.UserRole;
import com.eventhub.user.mapper.UserMapper;
import com.eventhub.user.repository.UserEntity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.transaction.AfterTransaction;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class UserFoundationIntegrationTest {

    private static final String TEST_PREFIX = "phase5-user-";

    @Autowired
    private UserAccountApi userAccountApi;

    @Autowired
    private UserCredentialQueryApi userCredentialQueryApi;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void createsCanonicalUserAndMapsUserRoleInBothDirections() {
        String canonicalUsername = uniqueUsername("create");
        String encodedPassword = "encoded-" + UUID.randomUUID();

        UserProfile profile = userAccountApi.createAccount(new CreateUserAccountCommand(
                "  " + canonicalUsername.toUpperCase(Locale.ROOT) + "  ",
                encodedPassword,
                "  Alice User  "));

        assertThat(profile.getId()).isPositive();
        assertThat(profile.getUsername()).isEqualTo(canonicalUsername);
        assertThat(profile.getDisplayName()).isEqualTo("Alice User");
        assertThat(profile.getRole()).isEqualTo(UserRole.USER);
        assertThat(profile.getCreatedAt()).isNotNull();
        assertThat(profile.getUpdatedAt()).isNotNull();
        assertThat(databaseRole(profile.getId())).isEqualTo("USER");

        UserEntity persisted = userMapper.selectById(profile.getId());
        assertThat(persisted.getRole()).isEqualTo(UserRole.USER);
        assertThat(persisted.getPasswordHash()).isEqualTo(encodedPassword);
    }

    @Test
    void readsAdminRoleThroughCredentialPublicApiWithoutCreatingAdminThroughAccountApi() {
        String username = uniqueUsername("admin");
        String encodedPassword = "encoded-" + UUID.randomUUID();
        UserEntity admin = new UserEntity();
        admin.setUsername(username);
        admin.setPasswordHash(encodedPassword);
        admin.setDisplayName("Admin Fixture");
        admin.setRole(UserRole.ADMIN);

        assertThat(userMapper.insert(admin)).isEqualTo(1);
        assertThat(databaseRole(admin.getId())).isEqualTo("ADMIN");

        UserAuthenticationData data = userCredentialQueryApi
                .findByUsername("  " + username.toUpperCase(Locale.ROOT) + "  ")
                .orElseThrow();
        assertThat(data.getUserId()).isEqualTo(admin.getId());
        assertThat(data.getUsername()).isEqualTo(username);
        assertThat(data.getEncodedPassword()).isEqualTo(encodedPassword);
        assertThat(data.getRole()).isEqualTo(UserRole.ADMIN);
        assertThat(data.toString()).doesNotContain(encodedPassword);
    }

    @Test
    void translatesDatabaseUniqueViolationForCanonicalDuplicateUsername() {
        String username = uniqueUsername("duplicate");
        userAccountApi.createAccount(new CreateUserAccountCommand(
                "  " + username.toUpperCase(Locale.ROOT) + "  ",
                "encoded-first",
                null));

        assertThatThrownBy(() -> userAccountApi.createAccount(new CreateUserAccountCommand(
                username,
                "encoded-second",
                null)))
                .isInstanceOfSatisfying(UserException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(UserErrorCode.USERNAME_ALREADY_EXISTS));

        assertThat(countUsername(username)).isEqualTo(1);
    }

    @Test
    void returnsSeparatedProfileAndCredentialModelsAndUpdatesOnlyDisplayName() {
        String username = uniqueUsername("profile");
        String encodedPassword = "encoded-" + UUID.randomUUID();
        UserProfile created = userAccountApi.createAccount(new CreateUserAccountCommand(
                username,
                encodedPassword,
                null));

        UserAuthenticationData authenticationData = userCredentialQueryApi
                .findByUsername("  " + username.toUpperCase(Locale.ROOT) + "  ")
                .orElseThrow();
        assertThat(authenticationData.getEncodedPassword()).isEqualTo(encodedPassword);

        UserProfile updated = userAccountApi.updateDisplayName(
                created.getId(),
                new UpdateDisplayNameCommand("  Updated Name  "));
        assertThat(updated.getDisplayName()).isEqualTo("Updated Name");
        assertThat(updated.getUsername()).isEqualTo(username);
        assertThat(updated.getRole()).isEqualTo(UserRole.USER);

        UserProfile cleared = userAccountApi.updateDisplayName(
                created.getId(),
                new UpdateDisplayNameCommand(null));
        assertThat(cleared.getDisplayName()).isNull();
        assertThat(userMapper.selectById(created.getId()).getDisplayName()).isNull();

        assertThatThrownBy(() -> userAccountApi.updateDisplayName(
                created.getId(),
                new UpdateDisplayNameCommand("   ")))
                .isInstanceOfSatisfying(UserException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(UserErrorCode.INVALID_DISPLAY_NAME));
    }

    @Test
    void reportsMissingProfilesButKeepsMissingCredentialLookupNonEnumerating() {
        assertThatThrownBy(() -> userAccountApi.getProfile(Long.MAX_VALUE))
                .isInstanceOfSatisfying(UserException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(UserErrorCode.USER_NOT_FOUND));

        assertThat(userCredentialQueryApi.findByUsername(uniqueUsername("missing"))).isEmpty();
    }

    @AfterTransaction
    void verifiesTransactionRollbackRemovedSyntheticUsers() {
        Long residueCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE username LIKE ?",
                Long.class,
                TEST_PREFIX + "%");
        assertThat(residueCount).isZero();
    }

    private String databaseRole(long userId) {
        return jdbcTemplate.queryForObject(
                "SELECT role FROM users WHERE id = ?",
                String.class,
                userId);
    }

    private Long countUsername(String username) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE username = ?",
                Long.class,
                username);
    }

    private static String uniqueUsername(String scenario) {
        return TEST_PREFIX + scenario + '-' + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}

package com.eventhub.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.eventhub.notification.mapper.UserNotificationMapper;
import com.eventhub.notification.repository.UserNotificationEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class NotificationSchemaMigrationIntegrationTest {

    private static final String TEST_PREFIX = "phase11-b2-";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserNotificationMapper userNotificationMapper;

    @Test
    void recordsV5AndExposesTheFrozenTableMetadata() {
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = TRUE", Integer.class))
                .isEqualTo(5);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = FALSE", Integer.class))
                .isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT script FROM flyway_schema_history WHERE version = '5'", String.class))
                .isEqualTo("V5__create_user_notification.sql");

        List<ColumnMetadata> columns = jdbcTemplate.query("""
                SELECT COLUMN_NAME, DATA_TYPE, CHARACTER_MAXIMUM_LENGTH, IS_NULLABLE,
                       COLUMN_DEFAULT, COLLATION_NAME, EXTRA, DATETIME_PRECISION
                FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND table_name = 'user_notification'
                ORDER BY ORDINAL_POSITION
                """, (resultSet, rowNumber) -> new ColumnMetadata(
                resultSet.getString("COLUMN_NAME"),
                resultSet.getString("DATA_TYPE"),
                resultSet.getObject("CHARACTER_MAXIMUM_LENGTH", Long.class),
                resultSet.getString("IS_NULLABLE"),
                resultSet.getString("COLUMN_DEFAULT"),
                resultSet.getString("COLLATION_NAME"),
                resultSet.getString("EXTRA"),
                resultSet.getObject("DATETIME_PRECISION", Integer.class)));

        assertThat(columns).containsExactly(
                new ColumnMetadata("id", "bigint", null, "NO", null, null, "auto_increment", null),
                new ColumnMetadata("event_id", "varchar", 36L, "NO", null, "ascii_bin", "", null),
                new ColumnMetadata("user_id", "bigint", null, "NO", null, null, "", null),
                new ColumnMetadata("type", "varchar", 32L, "NO", null, "utf8mb4_0900_as_cs", "", null),
                new ColumnMetadata("business_reference", "varchar", 64L, "NO", null,
                        "utf8mb4_0900_as_cs", "", null),
                new ColumnMetadata("occurred_at", "datetime", null, "NO", null, null, "", 6),
                new ColumnMetadata("created_at", "datetime", null, "NO", "CURRENT_TIMESTAMP(6)", null,
                        "DEFAULT_GENERATED", 6));

        Map<String, String> constraints = jdbcTemplate.query("""
                SELECT CONSTRAINT_NAME, CONSTRAINT_TYPE
                FROM information_schema.table_constraints
                WHERE table_schema = DATABASE()
                  AND table_name = 'user_notification'
                """, resultSet -> {
            java.util.HashMap<String, String> result = new java.util.HashMap<>();
            while (resultSet.next()) {
                result.put(resultSet.getString("CONSTRAINT_NAME"), resultSet.getString("CONSTRAINT_TYPE"));
            }
            return result;
        });
        assertThat(constraints).containsExactlyInAnyOrderEntriesOf(Map.of(
                "PRIMARY", "PRIMARY KEY",
                "uk_user_notification_event", "UNIQUE",
                "uk_user_notification_business", "UNIQUE",
                "ck_user_notification_type", "CHECK"));

        CheckMetadata check = jdbcTemplate.queryForObject("""
                SELECT cc.CHECK_CLAUSE, tc.ENFORCED
                FROM information_schema.check_constraints cc
                JOIN information_schema.table_constraints tc
                  ON tc.CONSTRAINT_SCHEMA = cc.CONSTRAINT_SCHEMA
                 AND tc.CONSTRAINT_NAME = cc.CONSTRAINT_NAME
                WHERE cc.CONSTRAINT_SCHEMA = DATABASE()
                  AND tc.TABLE_NAME = 'user_notification'
                  AND cc.CONSTRAINT_NAME = 'ck_user_notification_type'
                """, (resultSet, rowNumber) -> new CheckMetadata(
                resultSet.getString("CHECK_CLAUSE"), resultSet.getString("ENFORCED")));
        assertThat(check.clause()).contains("TICKET_ISSUED");
        assertThat(check.enforced()).isEqualTo("YES");

        Set<String> indexes = Set.copyOf(jdbcTemplate.queryForList("""
                SELECT DISTINCT INDEX_NAME
                FROM information_schema.statistics
                WHERE table_schema = DATABASE()
                  AND table_name = 'user_notification'
                """, String.class));
        assertThat(indexes).containsExactlyInAnyOrder(
                "PRIMARY", "uk_user_notification_event", "uk_user_notification_business");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.table_constraints
                WHERE table_schema = DATABASE()
                  AND table_name = 'user_notification'
                  AND constraint_type = 'FOREIGN KEY'
                """, Integer.class)).isZero();
    }

    @Test
    void enforcesVocabularyAndIndependentIdempotencyConstraints() {
        String eventId = UUID.randomUUID().toString();
        String businessReference = TEST_PREFIX + "ticket-" + shortId();
        insert(eventId, "TICKET_ISSUED", businessReference);

        assertThatThrownBy(() -> insert(UUID.randomUUID().toString(), "ticket_issued",
                TEST_PREFIX + "lower-" + shortId()))
                .isInstanceOfSatisfying(DataAccessException.class,
                        exception -> assertConstraint(exception, "ck_user_notification_type", 3819));
        assertThatThrownBy(() -> insert(UUID.randomUUID().toString(), "UNKNOWN",
                TEST_PREFIX + "unknown-" + shortId()))
                .isInstanceOfSatisfying(DataAccessException.class,
                        exception -> assertConstraint(exception, "ck_user_notification_type", 3819));
        assertThatThrownBy(() -> insert(eventId, "TICKET_ISSUED",
                TEST_PREFIX + "other-" + shortId()))
                .isInstanceOfSatisfying(DataAccessException.class,
                        exception -> assertConstraint(exception, "uk_user_notification_event", 1062));
        assertThatThrownBy(() -> insert(UUID.randomUUID().toString(), "TICKET_ISSUED", businessReference))
                .isInstanceOfSatisfying(DataAccessException.class,
                        exception -> assertConstraint(exception, "uk_user_notification_business", 1062));
    }

    @Test
    void mapperRoundTripsThePersistenceFoundationAndDatabaseTimestamp() {
        UserNotificationEntity entity = new UserNotificationEntity();
        entity.setEventId(UUID.randomUUID().toString());
        entity.setUserId(91_001L);
        entity.setType("TICKET_ISSUED");
        entity.setBusinessReference(TEST_PREFIX + "mapper-" + shortId());
        entity.setOccurredAt(LocalDateTime.now(ZoneOffset.UTC).withNano(123_456_000));

        assertThat(userNotificationMapper.insert(entity)).isEqualTo(1);
        assertThat(entity.getId()).isPositive();

        UserNotificationEntity persisted = userNotificationMapper.selectById(entity.getId());
        assertThat(persisted.getEventId()).isEqualTo(entity.getEventId());
        assertThat(persisted.getUserId()).isEqualTo(entity.getUserId());
        assertThat(persisted.getType()).isEqualTo("TICKET_ISSUED");
        assertThat(persisted.getBusinessReference()).isEqualTo(entity.getBusinessReference());
        assertThat(persisted.getOccurredAt()).isEqualTo(entity.getOccurredAt());
        assertThat(persisted.getCreatedAt()).isNotNull();
    }

    @AfterEach
    void cleanupAndVerifyNoSyntheticResidue() {
        jdbcTemplate.update("DELETE FROM user_notification WHERE business_reference LIKE ?", TEST_PREFIX + "%");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_notification WHERE business_reference LIKE ?",
                Integer.class, TEST_PREFIX + "%")).isZero();
    }

    private void insert(String eventId, String type, String businessReference) {
        jdbcTemplate.update("""
                INSERT INTO user_notification
                    (event_id, user_id, type, business_reference, occurred_at)
                VALUES (?, ?, ?, ?, ?)
                """, eventId, 91_001L, type, businessReference, LocalDateTime.now(ZoneOffset.UTC));
    }

    private static void assertConstraint(Throwable exception, String constraintName, int vendorCode) {
        SQLException sqlException = findSqlException(exception);
        assertThat(sqlException.getMessage()).contains(constraintName);
        assertThat(sqlException.getErrorCode()).isEqualTo(vendorCode);
    }

    private static SQLException findSqlException(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof SQLException sqlException) {
                return sqlException;
            }
            current = current.getCause();
        }
        throw new AssertionError("Expected SQLException in cause chain", exception);
    }

    private static String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private record ColumnMetadata(
            String name,
            String dataType,
            Long length,
            String nullable,
            String defaultValue,
            String collation,
            String extra,
            Integer datetimePrecision) {
    }

    private record CheckMetadata(String clause, String enforced) {
    }
}

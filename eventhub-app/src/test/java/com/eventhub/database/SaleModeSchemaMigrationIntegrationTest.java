package com.eventhub.database;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import com.eventhub.event.api.model.SessionSaleMode;
import com.eventhub.event.mapper.EventSessionMapper;
import com.eventhub.event.repository.EventSessionEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class SaleModeSchemaMigrationIntegrationTest {

    private static final String TEST_PREFIX = "phase10-v4-";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EventSessionMapper eventSessionMapper;

    @Test
    void recordsV4AndExposesTheFrozenSaleModeMetadata() {
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = TRUE", Integer.class))
                .isEqualTo(5);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = FALSE", Integer.class))
                .isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT script FROM flyway_schema_history WHERE version = '4'", String.class))
                .isEqualTo("V4__add_event_session_sale_mode.sql");

        ColumnMetadata column = jdbcTemplate.queryForObject("""
                SELECT DATA_TYPE, CHARACTER_MAXIMUM_LENGTH, IS_NULLABLE,
                       COLUMN_DEFAULT, COLLATION_NAME
                FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND table_name = 'event_sessions'
                  AND column_name = 'sale_mode'
                """, (resultSet, rowNumber) -> new ColumnMetadata(
                resultSet.getString("DATA_TYPE"),
                resultSet.getInt("CHARACTER_MAXIMUM_LENGTH"),
                resultSet.getString("IS_NULLABLE"),
                resultSet.getString("COLUMN_DEFAULT"),
                resultSet.getString("COLLATION_NAME")));
        assertThat(column).isNotNull();
        assertThat(column.dataType()).isEqualToIgnoringCase("varchar");
        assertThat(column.length()).isEqualTo(16);
        assertThat(column.nullable()).isEqualTo("NO");
        assertThat(column.defaultValue()).isEqualTo("NORMAL");
        assertThat(column.collation()).isEqualTo("utf8mb4_0900_as_cs");

        CheckMetadata check = jdbcTemplate.queryForObject("""
                SELECT cc.CHECK_CLAUSE, tc.ENFORCED
                FROM information_schema.check_constraints cc
                JOIN information_schema.table_constraints tc
                  ON tc.CONSTRAINT_SCHEMA = cc.CONSTRAINT_SCHEMA
                 AND tc.CONSTRAINT_NAME = cc.CONSTRAINT_NAME
                WHERE cc.CONSTRAINT_SCHEMA = DATABASE()
                  AND tc.TABLE_NAME = 'event_sessions'
                  AND cc.CONSTRAINT_NAME = 'ck_event_sessions_sale_mode'
                """, (resultSet, rowNumber) -> new CheckMetadata(
                resultSet.getString("CHECK_CLAUSE"),
                resultSet.getString("ENFORCED")));
        assertThat(check).isNotNull();
        assertThat(check.clause()).contains("NORMAL", "FLASH");
        assertThat(check.enforced()).isEqualTo("YES");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.statistics
                WHERE table_schema = DATABASE()
                  AND table_name = 'event_sessions'
                  AND column_name = 'sale_mode'
                """, Integer.class)).isZero();
    }

    @Test
    void defaultsOmittedModeToNormalMapsBothEnumsAndRejectsInvalidVocabulary() {
        long eventId = insertEvent("roundtrip");
        long sessionId = insertSession(eventId, "default", null);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT sale_mode FROM event_sessions WHERE id = ?", String.class, sessionId))
                .isEqualTo("NORMAL");
        EventSessionEntity normal = eventSessionMapper.selectById(sessionId);
        assertThat(normal.getSaleMode()).isEqualTo(SessionSaleMode.NORMAL);

        jdbcTemplate.update("UPDATE event_sessions SET sale_mode = 'FLASH' WHERE id = ?", sessionId);
        EventSessionEntity flash = eventSessionMapper.selectById(sessionId);
        assertThat(flash.getSaleMode()).isEqualTo(SessionSaleMode.FLASH);

        for (String invalid : new String[] {"normal", "flash", "unknown"}) {
            assertThatThrownBy(() -> insertSession(eventId, invalid, invalid))
                    .isInstanceOfSatisfying(DataAccessException.class,
                            exception -> assertConstraint(exception, "ck_event_sessions_sale_mode", 3819));
        }
    }

    @AfterEach
    void cleanupAndVerifyNoSyntheticResidue() {
        jdbcTemplate.update("DELETE s FROM event_sessions s JOIN events e ON e.id = s.event_id "
                + "WHERE e.title LIKE ?", TEST_PREFIX + "%");
        jdbcTemplate.update("DELETE FROM events WHERE title LIKE ?", TEST_PREFIX + "%");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM event_sessions WHERE name LIKE ?",
                Integer.class, TEST_PREFIX + "%")).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM events WHERE title LIKE ?",
                Integer.class, TEST_PREFIX + "%")).isZero();
    }

    private long insertEvent(String scenario) {
        String title = TEST_PREFIX + scenario + '-' + shortId();
        jdbcTemplate.update("INSERT INTO events (organizer_user_id, title, status) VALUES (?, ?, 'DRAFT')",
                10_004L, title);
        return jdbcTemplate.queryForObject("SELECT id FROM events WHERE title = ?", Long.class, title);
    }

    private long insertSession(long eventId, String scenario, String saleMode) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        String name = TEST_PREFIX + scenario + '-' + shortId();
        if (saleMode == null) {
            jdbcTemplate.update("""
                    INSERT INTO event_sessions
                        (event_id, name, sale_start_at, sale_end_at, start_at, end_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, eventId, name, now.minusMinutes(5), now.plusMinutes(5),
                    now.plusHours(1), now.plusHours(2));
        } else {
            jdbcTemplate.update("""
                    INSERT INTO event_sessions
                        (event_id, name, sale_mode, sale_start_at, sale_end_at, start_at, end_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, eventId, name, saleMode, now.minusMinutes(5), now.plusMinutes(5),
                    now.plusHours(1), now.plusHours(2));
        }
        return jdbcTemplate.queryForObject(
                "SELECT id FROM event_sessions WHERE name = ?", Long.class, name);
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
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private record ColumnMetadata(
            String dataType,
            int length,
            String nullable,
            String defaultValue,
            String collation) {
    }

    private record CheckMetadata(String clause, String enforced) {
    }
}

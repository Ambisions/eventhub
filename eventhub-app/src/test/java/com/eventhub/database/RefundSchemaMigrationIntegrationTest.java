package com.eventhub.database;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

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
class RefundSchemaMigrationIntegrationTest {

    private static final String ORDER_PREFIX = "P9V3-O-";
    private static final String TICKET_PREFIX = "P9V3-TKT-";
    private static final AtomicLong SYNTHETIC_ID = new AtomicLong(9_000_000_000L);

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void recordsV3AndExposesTheFrozenSchemaMetadata() {
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = TRUE", Integer.class))
                .isEqualTo(5);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = FALSE", Integer.class))
                .isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT script FROM flyway_schema_history WHERE version = '3'", String.class))
                .isEqualTo("V3__add_refunded_order_and_ticket_status.sql");

        String orderCheck = checkClause("ck_ticket_order_status");
        assertThat(orderCheck).contains("WAIT_PAY", "PAID", "CLOSED", "CANCELLED", "REFUNDED");
        String ticketCheck = checkClause("ck_ticket_status");
        assertThat(ticketCheck).contains("VALID", "USED", "REFUNDED");

        GeneratedColumnMetadata generatedColumn = jdbcTemplate.queryForObject("""
                SELECT DATA_TYPE, EXTRA, GENERATION_EXPRESSION
                FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND table_name = 'ticket_order'
                  AND column_name = 'purchase_guard'
                """, (resultSet, rowNumber) -> new GeneratedColumnMetadata(
                resultSet.getString("DATA_TYPE"),
                resultSet.getString("EXTRA"),
                resultSet.getString("GENERATION_EXPRESSION")));
        assertThat(generatedColumn).isNotNull();
        assertThat(generatedColumn.dataType()).isEqualToIgnoringCase("tinyint");
        assertThat(generatedColumn.extra()).containsIgnoringCase("STORED GENERATED");
        assertThat(normalize(generatedColumn.expression()))
                .contains("WAIT_PAY", "PAID", "REFUNDED", "ELSE NULL");

        assertThat(indexColumns("ticket_order", "uk_ticket_order_effective_purchase"))
                .containsExactly("user_id", "session_id", "purchase_guard");
        assertUniqueIndex("ticket_order", "uk_ticket_order_effective_purchase");
        assertUniqueIndex("ticket", "uk_ticket_ticket_no");
        assertUniqueIndex("ticket", "uk_ticket_order");
        assertUniqueIndex("ticket", "uk_ticket_user_session");

        assertThat(showCreateTable("ticket_order"))
                .contains("`purchase_guard` tinyint GENERATED ALWAYS AS")
                .contains("STORED")
                .contains("`uk_ticket_order_effective_purchase`");
        assertThat(showCreateTable("ticket"))
                .contains("`ck_ticket_status`")
                .contains("REFUNDED");
    }

    @Test
    void enforcesOrderAndTicketStatusChecksAndGeneratedGuardValues() {
        List<GuardExpectation> expectations = List.of(
                new GuardExpectation("WAIT_PAY", 1),
                new GuardExpectation("PAID", 1),
                new GuardExpectation("REFUNDED", 1),
                new GuardExpectation("CLOSED", null),
                new GuardExpectation("CANCELLED", null));

        for (GuardExpectation expectation : expectations) {
            String orderNo = orderNo(expectation.status());
            insertOrder(orderNo, nextId(), nextId(), expectation.status());
            Integer guard = jdbcTemplate.queryForObject(
                    "SELECT purchase_guard FROM ticket_order WHERE order_no = ?",
                    Integer.class,
                    orderNo);
            assertThat(guard).isEqualTo(expectation.guard());
        }

        for (String invalid : List.of("UNKNOWN", "wait_pay", "refunded")) {
            assertThatThrownBy(() -> insertOrder(orderNo("invalid"), nextId(), nextId(), invalid))
                    .isInstanceOfSatisfying(DataAccessException.class,
                            exception -> assertSqlConstraint(exception, "ck_ticket_order_status", 3819));
        }

        for (String status : List.of("VALID", "USED", "REFUNDED")) {
            String ticketNo = ticketNo(status);
            insertTicket(ticketNo, nextId(), nextId(), nextId(), status);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT status FROM ticket WHERE ticket_no = ?", String.class, ticketNo))
                    .isEqualTo(status);
        }

        for (String invalid : List.of("UNKNOWN", "valid", "used", "refunded")) {
            assertThatThrownBy(() -> insertTicket(ticketNo("invalid"), nextId(), nextId(), nextId(), invalid))
                    .isInstanceOfSatisfying(DataAccessException.class,
                            exception -> assertSqlConstraint(exception, "ck_ticket_status", 3819));
        }
    }

    @Test
    void preservesHistoricalPurchaseEligibilitySemantics() {
        long refundedUser = nextId();
        long refundedSession = nextId();
        insertOrder(orderNo("refunded"), refundedUser, refundedSession, "REFUNDED");

        assertThatThrownBy(() -> insertOrder(
                orderNo("refunded-duplicate"), refundedUser, refundedSession, "WAIT_PAY"))
                .isInstanceOfSatisfying(DataAccessException.class,
                        exception -> assertSqlConstraint(
                                exception, "uk_ticket_order_effective_purchase", 1062));

        assertTerminalHistoryAllowsNewPurchase("CLOSED");
        assertTerminalHistoryAllowsNewPurchase("CANCELLED");
    }

    @Test
    void preservesAllTicketUniqueConstraints() {
        String originalTicketNo = ticketNo("base");
        long originalOrderId = nextId();
        long originalUserId = nextId();
        long originalSessionId = nextId();
        insertTicket(originalTicketNo, originalOrderId, originalUserId, originalSessionId, "VALID");

        assertThatThrownBy(() -> insertTicket(
                originalTicketNo, nextId(), nextId(), nextId(), "VALID"))
                .isInstanceOfSatisfying(DataAccessException.class,
                        exception -> assertSqlConstraint(exception, "uk_ticket_ticket_no", 1062));
        assertThatThrownBy(() -> insertTicket(
                ticketNo("same-order"), originalOrderId, nextId(), nextId(), "VALID"))
                .isInstanceOfSatisfying(DataAccessException.class,
                        exception -> assertSqlConstraint(exception, "uk_ticket_order", 1062));
        assertThatThrownBy(() -> insertTicket(
                ticketNo("same-user-session"), nextId(), originalUserId, originalSessionId, "VALID"))
                .isInstanceOfSatisfying(DataAccessException.class,
                        exception -> assertSqlConstraint(exception, "uk_ticket_user_session", 1062));
    }

    @AfterEach
    void cleanupAndVerifyNoSyntheticResidue() {
        jdbcTemplate.update("DELETE FROM ticket WHERE ticket_no LIKE ?", TICKET_PREFIX + "%");
        jdbcTemplate.update("DELETE FROM ticket_order WHERE order_no LIKE ?", ORDER_PREFIX + "%");

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ticket WHERE ticket_no LIKE ?", Integer.class, TICKET_PREFIX + "%"))
                .isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ticket_order WHERE order_no LIKE ?", Integer.class, ORDER_PREFIX + "%"))
                .isZero();
    }

    private void assertTerminalHistoryAllowsNewPurchase(String terminalStatus) {
        long userId = nextId();
        long sessionId = nextId();
        String historicalOrderNo = orderNo(terminalStatus);
        String activeOrderNo = orderNo(terminalStatus + "-active");

        insertOrder(historicalOrderNo, userId, sessionId, terminalStatus);
        insertOrder(activeOrderNo, userId, sessionId, "WAIT_PAY");

        assertThat(jdbcTemplate.queryForObject(
                "SELECT purchase_guard FROM ticket_order WHERE order_no = ?",
                Integer.class,
                historicalOrderNo)).isNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT purchase_guard FROM ticket_order WHERE order_no = ?",
                Integer.class,
                activeOrderNo)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ticket_order WHERE user_id = ? AND session_id = ?",
                Integer.class,
                userId,
                sessionId)).isEqualTo(2);
    }

    private String checkClause(String constraintName) {
        return jdbcTemplate.queryForObject("""
                SELECT CHECK_CLAUSE
                FROM information_schema.check_constraints
                WHERE constraint_schema = DATABASE()
                  AND constraint_name = ?
                """, String.class, constraintName);
    }

    private List<String> indexColumns(String tableName, String indexName) {
        return jdbcTemplate.queryForList("""
                SELECT COLUMN_NAME
                FROM information_schema.statistics
                WHERE table_schema = DATABASE()
                  AND table_name = ?
                  AND index_name = ?
                ORDER BY SEQ_IN_INDEX
                """, String.class, tableName, indexName);
    }

    private void assertUniqueIndex(String tableName, String indexName) {
        Integer nonUnique = jdbcTemplate.queryForObject("""
                SELECT MAX(NON_UNIQUE)
                FROM information_schema.statistics
                WHERE table_schema = DATABASE()
                  AND table_name = ?
                  AND index_name = ?
                """, Integer.class, tableName, indexName);
        assertThat(nonUnique).isZero();
    }

    private String showCreateTable(String tableName) {
        return jdbcTemplate.queryForObject(
                "SHOW CREATE TABLE " + tableName,
                (resultSet, rowNumber) -> resultSet.getString(2));
    }

    private void insertOrder(String orderNo, long userId, long sessionId, String status) {
        jdbcTemplate.update("""
                INSERT INTO ticket_order (order_no, user_id, session_id, status, expire_at)
                VALUES (?, ?, ?, ?, ?)
                """, orderNo, userId, sessionId, status, utcNow().plusMinutes(15));
    }

    private void insertTicket(String ticketNo, long orderId, long userId, long sessionId, String status) {
        jdbcTemplate.update("""
                INSERT INTO ticket (ticket_no, order_id, user_id, session_id, status)
                VALUES (?, ?, ?, ?, ?)
                """, ticketNo, orderId, userId, sessionId, status);
    }

    private static void assertSqlConstraint(Throwable exception, String constraintName, int vendorCode) {
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

    private static String normalize(String value) {
        return value.replace('`', ' ')
                .replaceAll("\\s+", " ")
                .toUpperCase(Locale.ROOT);
    }

    private static String orderNo(String scenario) {
        return ORDER_PREFIX + scenario.toUpperCase(Locale.ROOT) + '-'
                + UUID.randomUUID().toString().replace("-", "");
    }

    private static String ticketNo(String scenario) {
        String compact = scenario.toUpperCase(Locale.ROOT).replace('-', '_');
        return TICKET_PREFIX + compact + '-'
                + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private static long nextId() {
        return SYNTHETIC_ID.incrementAndGet();
    }

    private static LocalDateTime utcNow() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    private record GeneratedColumnMetadata(String dataType, String extra, String expression) {
    }

    private record GuardExpectation(String status, Integer guard) {
    }
}

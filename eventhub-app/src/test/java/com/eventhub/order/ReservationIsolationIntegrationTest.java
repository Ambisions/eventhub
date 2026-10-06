package com.eventhub.order;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import javax.sql.DataSource;

import com.eventhub.inventory.api.InventoryReservationApi;
import com.eventhub.order.api.ReservationApi;
import com.eventhub.order.api.model.OrderStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(ReservationIsolationIntegrationTest.IsolationProbeConfiguration.class)
class ReservationIsolationIntegrationTest {

    private static final String TEST_PREFIX = "phase6-isolation-";
    private static final String ENCODED_PASSWORD = "{bcrypt}$2a$10$phase6.isolation.credential";

    @Autowired
    private ReservationApi reservationApi;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private IsolationObservingInventoryReservationApi isolationObserver;

    @Test
    void reserveOverridesMysqlDefaultsWithReadCommittedOnTheBoundTransaction() {
        assertThat(queryIsolation("@@global.transaction_isolation")).isEqualTo("REPEATABLE-READ");
        assertThat(queryIsolation("@@session.transaction_isolation")).isEqualTo("REPEATABLE-READ");

        long userId = createUser();
        long sessionId = createFixture();
        isolationObserver.reset();

        var created = reservationApi.reserve(userId, sessionId);

        IsolationObservation observation = isolationObserver.requireObservation();
        assertThat(observation.transactionActive()).isTrue();
        assertThat(observation.connectionBoundToTransaction()).isTrue();
        assertThat(observation.jdbcIsolation()).isEqualTo(Connection.TRANSACTION_READ_COMMITTED);
        assertThat(observation.mysqlIsolation()).isEqualTo("READ-COMMITTED");
        assertThat(created.status()).isEqualTo(OrderStatus.WAIT_PAY);
        assertThat(inventory(sessionId)).isEqualTo(new InventoryState(2, 1, 1, 0));
    }

    @Test
    void reserveFlashAlsoUsesReadCommittedOnTheBoundTransaction() {
        long userId = createUser();
        long sessionId = createFixture("FLASH");
        isolationObserver.reset();

        var created = reservationApi.reserveFlash(userId, sessionId);

        IsolationObservation observation = isolationObserver.requireObservation();
        assertThat(observation.transactionActive()).isTrue();
        assertThat(observation.connectionBoundToTransaction()).isTrue();
        assertThat(observation.jdbcIsolation()).isEqualTo(Connection.TRANSACTION_READ_COMMITTED);
        assertThat(observation.mysqlIsolation()).isEqualTo("READ-COMMITTED");
        assertThat(created.status()).isEqualTo(OrderStatus.WAIT_PAY);
        assertThat(inventory(sessionId)).isEqualTo(new InventoryState(2, 1, 1, 0));
    }

    @AfterEach
    void cleanupAndVerifyNoIsolationTestResidue() {
        jdbcTemplate.update("DELETE o FROM ticket_order o JOIN event_sessions s ON s.id = o.session_id "
                + "WHERE s.name LIKE ?", TEST_PREFIX + "%");
        jdbcTemplate.update("DELETE i FROM event_inventory i JOIN event_sessions s ON s.id = i.session_id "
                + "WHERE s.name LIKE ?", TEST_PREFIX + "%");
        jdbcTemplate.update("DELETE FROM event_sessions WHERE name LIKE ?", TEST_PREFIX + "%");
        jdbcTemplate.update("DELETE FROM events WHERE title LIKE ?", TEST_PREFIX + "%");
        jdbcTemplate.update("DELETE FROM users WHERE username LIKE ?", TEST_PREFIX + "%");

        assertThat(count("SELECT COUNT(*) FROM ticket_order o "
                + "JOIN event_sessions s ON s.id = o.session_id WHERE s.name LIKE ?")).isZero();
        assertThat(count("SELECT COUNT(*) FROM event_inventory i "
                + "JOIN event_sessions s ON s.id = i.session_id WHERE s.name LIKE ?")).isZero();
        assertThat(count("SELECT COUNT(*) FROM event_sessions WHERE name LIKE ?")).isZero();
        assertThat(count("SELECT COUNT(*) FROM events WHERE title LIKE ?")).isZero();
        assertThat(count("SELECT COUNT(*) FROM users WHERE username LIKE ?")).isZero();
    }

    private String queryIsolation(String variable) {
        return jdbcTemplate.queryForObject("SELECT " + variable, String.class);
    }

    private long createUser() {
        String username = TEST_PREFIX + shortId();
        jdbcTemplate.update("INSERT INTO users (username, password_hash, display_name, role) "
                        + "VALUES (?, ?, ?, 'USER')",
                username, ENCODED_PASSWORD, "isolation proof");
        return jdbcTemplate.queryForObject("SELECT id FROM users WHERE username = ?", Long.class, username);
    }

    private long createFixture() {
        return createFixture("NORMAL");
    }

    private long createFixture(String saleMode) {
        String suffix = shortId();
        String title = TEST_PREFIX + suffix;
        jdbcTemplate.update("INSERT INTO events (organizer_user_id, title, status) "
                + "VALUES (?, ?, 'PUBLISHED')", 9_610L, title);
        long eventId = jdbcTemplate.queryForObject("SELECT id FROM events WHERE title = ?", Long.class, title);

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        String sessionName = TEST_PREFIX + suffix;
        jdbcTemplate.update("INSERT INTO event_sessions "
                        + "(event_id, name, sale_mode, sale_start_at, sale_end_at, start_at, end_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                eventId, sessionName, saleMode, now.minusMinutes(5), now.plusMinutes(5),
                now.plusHours(3), now.plusHours(5));
        long sessionId = jdbcTemplate.queryForObject(
                "SELECT id FROM event_sessions WHERE name = ?", Long.class, sessionName);
        jdbcTemplate.update("INSERT INTO event_inventory "
                        + "(session_id, total_stock, available_stock, locked_stock, sold_stock) "
                        + "VALUES (?, 2, 2, 0, 0)",
                sessionId);
        return sessionId;
    }

    private InventoryState inventory(long sessionId) {
        return jdbcTemplate.queryForObject(
                "SELECT total_stock, available_stock, locked_stock, sold_stock "
                        + "FROM event_inventory WHERE session_id = ?",
                (resultSet, rowNum) -> new InventoryState(
                        resultSet.getInt("total_stock"),
                        resultSet.getInt("available_stock"),
                        resultSet.getInt("locked_stock"),
                        resultSet.getInt("sold_stock")),
                sessionId);
    }

    private long count(String sql) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, TEST_PREFIX + "%");
        return value == null ? 0 : value;
    }

    private static String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private record InventoryState(int total, int available, int locked, int sold) {
    }

    private record IsolationObservation(
            boolean transactionActive,
            boolean connectionBoundToTransaction,
            int jdbcIsolation,
            String mysqlIsolation) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class IsolationProbeConfiguration {

        @Bean
        @Primary
        IsolationObservingInventoryReservationApi isolationObservingInventoryReservationApi(
                @Qualifier("inventoryService") InventoryReservationApi delegate,
                DataSource dataSource) {
            return new IsolationObservingInventoryReservationApi(delegate, dataSource);
        }
    }

    static final class IsolationObservingInventoryReservationApi implements InventoryReservationApi {

        private final InventoryReservationApi delegate;
        private final DataSource dataSource;
        private final AtomicReference<IsolationObservation> observation = new AtomicReference<>();

        private IsolationObservingInventoryReservationApi(
                InventoryReservationApi delegate,
                DataSource dataSource) {
            this.delegate = delegate;
            this.dataSource = dataSource;
        }

        @Override
        public void lockOne(long sessionId) {
            Connection connection = DataSourceUtils.getConnection(dataSource);
            try {
                observation.set(new IsolationObservation(
                        TransactionSynchronizationManager.isActualTransactionActive(),
                        DataSourceUtils.isConnectionTransactional(connection, dataSource),
                        connection.getTransactionIsolation(),
                        readMysqlIsolation(connection)));
            } catch (SQLException exception) {
                throw new IllegalStateException("Could not inspect reservation transaction isolation", exception);
            } finally {
                DataSourceUtils.releaseConnection(connection, dataSource);
            }
            delegate.lockOne(sessionId);
        }

        @Override
        public void confirmSoldOne(long sessionId) {
            delegate.confirmSoldOne(sessionId);
        }

        @Override
        public void releaseOne(long sessionId) {
            delegate.releaseOne(sessionId);
        }

        private void reset() {
            observation.set(null);
        }

        private IsolationObservation requireObservation() {
            IsolationObservation current = observation.get();
            if (current == null) {
                throw new AssertionError("Reservation transaction was not observed");
            }
            return current;
        }

        private static String readMysqlIsolation(Connection connection) throws SQLException {
            try (PreparedStatement statement = connection.prepareStatement("SELECT @@transaction_isolation");
                    ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new SQLException("MySQL returned no transaction isolation row");
                }
                return resultSet.getString(1);
            }
        }
    }
}

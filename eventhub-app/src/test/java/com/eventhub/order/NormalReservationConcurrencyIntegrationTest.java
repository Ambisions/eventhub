package com.eventhub.order;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.eventhub.inventory.api.error.InventoryErrorCode;
import com.eventhub.inventory.api.error.InventoryException;
import com.eventhub.order.api.ReservationApi;
import com.eventhub.order.api.error.OrderErrorCode;
import com.eventhub.order.api.error.OrderException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class NormalReservationConcurrencyIntegrationTest {

    private static final String TEST_PREFIX = "phase6-order-race-";
    private static final String ENCODED_PASSWORD = "{bcrypt}$2a$10$phase6.synthetic.credential";
    private static final int CONCURRENCY_TIMEOUT_SECONDS = 60;

    @Autowired
    private ReservationApi reservationApi;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void oneHundredUsersCompetingForOneUnitProduceExactlyOneOrderAndOneLock() throws Exception {
        Fixture fixture = createFixture("stock-one", 1);
        List<Long> users = createUsers("stock-one", 100);

        List<Outcome> outcomes = runConcurrently(users.stream()
                .map(userId -> (ReservationCall) () -> reservationApi.reserve(userId, fixture.sessionId()))
                .toList());

        assertThat(outcomes).filteredOn(outcome -> outcome.type() == OutcomeType.SUCCESS).hasSize(1);
        assertThat(outcomes).filteredOn(outcome -> outcome.type() == OutcomeType.SOLD_OUT).hasSize(99);
        assertThat(outcomes).allSatisfy(outcome -> assertThat(outcome.type())
                .isIn(OutcomeType.SUCCESS, OutcomeType.SOLD_OUT));
        assertThat(activeOrderCount(fixture.sessionId())).isEqualTo(1);
        assertThat(inventory(fixture.sessionId())).isEqualTo(new InventoryState(1, 0, 1, 0));
        assertInventoryInvariant(fixture.sessionId());
    }

    @Test
    void sameUserCompetitionIsRejectedByEffectivePurchaseUniqueConstraintBeforeExtraLocks() throws Exception {
        Fixture fixture = createFixture("same-user", 10);
        long userId = createUsers("same-user", 1).get(0);
        List<ReservationCall> calls = new ArrayList<>();
        for (int index = 0; index < 10; index++) {
            calls.add(() -> reservationApi.reserve(userId, fixture.sessionId()));
        }

        List<Outcome> outcomes = runConcurrently(calls);

        assertThat(outcomes).filteredOn(outcome -> outcome.type() == OutcomeType.SUCCESS).hasSize(1);
        List<Outcome> duplicates = outcomes.stream()
                .filter(outcome -> outcome.type() == OutcomeType.ACTIVE_PURCHASE_EXISTS)
                .toList();
        assertThat(duplicates).hasSize(9);
        assertThat(duplicates).allSatisfy(outcome -> {
            assertThat(outcome.cause()).isInstanceOf(OrderException.class);
            assertThat(hasEffectivePurchaseDuplicate(outcome.cause())).isTrue();
        });
        assertThat(activeOrderCount(fixture.sessionId())).isEqualTo(1);
        assertThat(inventory(fixture.sessionId())).isEqualTo(new InventoryState(10, 9, 1, 0));
        assertInventoryInvariant(fixture.sessionId());
    }

    @AfterEach
    void cleanupAndVerifyNoConcurrencyResidue() {
        jdbcTemplate.update("DELETE o FROM ticket_order o JOIN event_sessions s ON s.id = o.session_id "
                + "WHERE s.name LIKE ?", TEST_PREFIX + "%");
        jdbcTemplate.update("DELETE i FROM event_inventory i JOIN event_sessions s ON s.id = i.session_id "
                + "WHERE s.name LIKE ?", TEST_PREFIX + "%");
        assertThat(count("SELECT COUNT(*) FROM ticket_order o JOIN event_sessions s ON s.id = o.session_id "
                + "WHERE s.name LIKE ?", TEST_PREFIX + "%")).isZero();
        assertThat(count("SELECT COUNT(*) FROM event_inventory i JOIN event_sessions s ON s.id = i.session_id "
                + "WHERE s.name LIKE ?", TEST_PREFIX + "%")).isZero();

        jdbcTemplate.update("DELETE FROM event_sessions WHERE name LIKE ?", TEST_PREFIX + "%");
        jdbcTemplate.update("DELETE FROM events WHERE title LIKE ?", TEST_PREFIX + "%");
        jdbcTemplate.update("DELETE FROM users WHERE username LIKE ?", TEST_PREFIX + "%");

        assertThat(count("SELECT COUNT(*) FROM event_sessions WHERE name LIKE ?", TEST_PREFIX + "%")).isZero();
        assertThat(count("SELECT COUNT(*) FROM events WHERE title LIKE ?", TEST_PREFIX + "%")).isZero();
        assertThat(count("SELECT COUNT(*) FROM users WHERE username LIKE ?", TEST_PREFIX + "%")).isZero();
    }

    private List<Outcome> runConcurrently(List<ReservationCall> calls) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(calls.size());
        CountDownLatch ready = new CountDownLatch(calls.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Outcome>> futures = new ArrayList<>();
        try {
            for (ReservationCall call : calls) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        return new Outcome(OutcomeType.UNEXPECTED, new IllegalStateException("start latch timed out"));
                    }
                    try {
                        call.reserve();
                        return new Outcome(OutcomeType.SUCCESS, null);
                    } catch (InventoryException exception) {
                        if (exception.getErrorCode() == InventoryErrorCode.SOLD_OUT) {
                            return new Outcome(OutcomeType.SOLD_OUT, exception);
                        }
                        return new Outcome(OutcomeType.UNEXPECTED, exception);
                    } catch (OrderException exception) {
                        if (exception.getErrorCode() == OrderErrorCode.ACTIVE_PURCHASE_EXISTS) {
                            return new Outcome(OutcomeType.ACTIVE_PURCHASE_EXISTS, exception);
                        }
                        return new Outcome(OutcomeType.UNEXPECTED, exception);
                    } catch (RuntimeException exception) {
                        return new Outcome(OutcomeType.UNEXPECTED, exception);
                    }
                }));
            }
            assertThat(ready.await(15, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            executor.shutdown();
            assertThat(executor.awaitTermination(CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();

            List<Outcome> outcomes = new ArrayList<>();
            for (Future<Outcome> future : futures) {
                assertThat(future.isDone()).isTrue();
                outcomes.add(future.get(1, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private Fixture createFixture(String scenario, int stock) {
        String suffix = scenario + '-' + shortId();
        String title = TEST_PREFIX + suffix;
        jdbcTemplate.update("INSERT INTO events (organizer_user_id, title, status) "
                + "VALUES (?, ?, 'PUBLISHED')", 9_603L, title);
        long eventId = jdbcTemplate.queryForObject("SELECT id FROM events WHERE title = ?", Long.class, title);

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        String sessionName = TEST_PREFIX + suffix;
        jdbcTemplate.update("INSERT INTO event_sessions "
                        + "(event_id, name, sale_start_at, sale_end_at, start_at, end_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                eventId, sessionName, now.minusMinutes(5), now.plusMinutes(5),
                now.plusHours(3), now.plusHours(5));
        long sessionId = jdbcTemplate.queryForObject(
                "SELECT id FROM event_sessions WHERE name = ?", Long.class, sessionName);
        jdbcTemplate.update("INSERT INTO event_inventory "
                        + "(session_id, total_stock, available_stock, locked_stock, sold_stock) "
                        + "VALUES (?, ?, ?, 0, 0)",
                sessionId, stock, stock);
        return new Fixture(eventId, sessionId);
    }

    private List<Long> createUsers(String scenario, int count) {
        List<Long> ids = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            String username = TEST_PREFIX + scenario + '-' + index + '-' + shortId();
            jdbcTemplate.update("INSERT INTO users (username, password_hash, display_name, role) "
                            + "VALUES (?, ?, ?, 'USER')",
                    username, ENCODED_PASSWORD, scenario);
            ids.add(jdbcTemplate.queryForObject(
                    "SELECT id FROM users WHERE username = ?", Long.class, username));
        }
        return ids;
    }

    private long activeOrderCount(long sessionId) {
        return count("SELECT COUNT(*) FROM ticket_order WHERE session_id = ? "
                + "AND status IN ('WAIT_PAY', 'PAID')", sessionId);
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

    private void assertInventoryInvariant(long sessionId) {
        InventoryState state = inventory(sessionId);
        assertThat(state.total()).isEqualTo(state.available() + state.locked() + state.sold());
        assertThat(state.available()).isNotNegative();
        assertThat(state.locked()).isNotNegative();
        assertThat(state.sold()).isNotNegative();
    }

    private long count(String sql, Object value) {
        Long count = jdbcTemplate.queryForObject(sql, Long.class, value);
        return count == null ? 0 : count;
    }

    private static boolean hasEffectivePurchaseDuplicate(Throwable throwable) {
        Throwable current = throwable;
        boolean duplicateKeySeen = false;
        while (current != null) {
            if (current instanceof DuplicateKeyException) {
                duplicateKeySeen = true;
            }
            if (current instanceof SQLException sqlException
                    && sqlException.getErrorCode() == 1062
                    && "23000".equals(sqlException.getSQLState())
                    && sqlException.getMessage() != null
                    && sqlException.getMessage().toLowerCase(Locale.ROOT)
                    .contains("uk_ticket_order_effective_purchase")) {
                return duplicateKeySeen;
            }
            current = current.getCause();
        }
        return false;
    }

    private static String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    @FunctionalInterface
    private interface ReservationCall {
        void reserve();
    }

    private enum OutcomeType {
        SUCCESS,
        SOLD_OUT,
        ACTIVE_PURCHASE_EXISTS,
        UNEXPECTED
    }

    private record Outcome(OutcomeType type, Throwable cause) {
    }

    private record Fixture(long eventId, long sessionId) {
    }

    private record InventoryState(int total, int available, int locked, int sold) {
    }
}

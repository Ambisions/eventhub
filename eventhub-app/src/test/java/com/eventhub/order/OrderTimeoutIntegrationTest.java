package com.eventhub.order;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.eventhub.inventory.api.error.InventoryErrorCode;
import com.eventhub.inventory.api.error.InventoryException;
import com.eventhub.order.api.OrderCommandApi;
import com.eventhub.order.api.OrderTimeoutApi;
import com.eventhub.order.api.error.OrderErrorCode;
import com.eventhub.order.api.error.OrderException;
import com.eventhub.order.mapper.OrderMapper;
import com.eventhub.order.service.OrderTimeoutProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import(OrderTimeoutIntegrationTest.ControlledClockConfiguration.class)
class OrderTimeoutIntegrationTest {

    private static final String TEST_PREFIX = "phase6-timeout-";
    private static final String ORDER_PREFIX = "P6T4-";
    private static final String ENCODED_PASSWORD = "{bcrypt}$2a$10$phase6.timeout.synthetic.credential";
    private static final Instant SCAN_INSTANT = Instant.parse("2001-01-01T12:00:00.100000Z");
    private static final LocalDateTime SCAN_NOW = LocalDateTime.ofInstant(SCAN_INSTANT, ZoneOffset.UTC);
    private static final LocalDateTime RACE_EXPIRE_AT = LocalDateTime.of(2001, 1, 1, 12, 0, 0);
    private static final Instant PAYMENT_INSTANT = Instant.parse("2001-01-01T11:59:59.900000Z");
    private static final int CONCURRENCY_TIMEOUT_SECONDS = 30;

    @Autowired
    private OrderTimeoutApi orderTimeoutApi;

    @Autowired
    private OrderTimeoutProcessor orderTimeoutProcessor;

    @Autowired
    private OrderCommandApi orderCommandApi;

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ThreadAwareClock controlledClock;

    @Test
    void closesPastAndExactExpiryOnceWhileLeavingFutureOrderUnchanged() {
        assertNoUnexpectedExpiredCandidates();
        OrderFixture past = createOrderFixture(
                "basic", new InventoryState(2, 1, 1, 0), SCAN_NOW.minusMinutes(1));
        OrderFixture exact = createOrderFixture(
                "exact", new InventoryState(1, 0, 1, 0), SCAN_NOW);
        OrderFixture future = createOrderFixture(
                "future", new InventoryState(3, 2, 1, 0), SCAN_NOW.plusNanos(1_000));

        var first = orderTimeoutApi.processExpiredBatch(10);

        assertThat(first.scanned()).isEqualTo(2);
        assertThat(first.closed()).isEqualTo(2);
        assertThat(first.closedOrders())
                .extracting("orderId", "userId", "sessionId")
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(
                                past.orderId(), past.userId(), past.sessionId()),
                        org.assertj.core.groups.Tuple.tuple(
                                exact.orderId(), exact.userId(), exact.sessionId()));
        assertThat(orderStatus(past.orderId())).isEqualTo("CLOSED");
        assertThat(inventory(past.sessionId())).isEqualTo(new InventoryState(2, 2, 0, 0));
        assertThat(orderStatus(exact.orderId())).isEqualTo("CLOSED");
        assertThat(inventory(exact.sessionId())).isEqualTo(new InventoryState(1, 1, 0, 0));
        assertThat(orderStatus(future.orderId())).isEqualTo("WAIT_PAY");
        assertThat(inventory(future.sessionId())).isEqualTo(new InventoryState(3, 2, 1, 0));

        var repeated = orderTimeoutApi.processExpiredBatch(10);

        assertThat(repeated.scanned()).isZero();
        assertThat(repeated.closed()).isZero();
        assertThat(repeated.closedOrders()).isEmpty();
        assertThat(orderTimeoutProcessor.closeExpiredOrder(future.orderId(), SCAN_NOW)).isEmpty();
        assertThat(inventory(past.sessionId())).isEqualTo(new InventoryState(2, 2, 0, 0));
        assertInventoryInvariant(past.sessionId());
        assertInventoryInvariant(exact.sessionId());
        assertInventoryInvariant(future.sessionId());
    }

    @Test
    void rollsBackClosedStateWhenInventoryReleaseFails() {
        OrderFixture fixture = createOrderFixture(
                "rollback", new InventoryState(1, 1, 0, 0), SCAN_NOW.minusMinutes(1));

        assertThatThrownBy(() -> orderTimeoutProcessor.closeExpiredOrder(fixture.orderId(), SCAN_NOW))
                .isInstanceOfSatisfying(InventoryException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(InventoryErrorCode.INVENTORY_STATE_CONFLICT));

        assertThat(orderStatus(fixture.orderId())).isEqualTo("WAIT_PAY");
        assertThat(inventory(fixture.sessionId())).isEqualTo(new InventoryState(1, 1, 0, 0));
        assertInventoryInvariant(fixture.sessionId());
    }

    @Test
    void isolatesOneFailedCandidateAndContinuesTheSameBatch() {
        assertNoUnexpectedExpiredCandidates();
        OrderFixture failing = createOrderFixture(
                "isolation-bad", new InventoryState(1, 1, 0, 0), SCAN_NOW.minusMinutes(2));
        OrderFixture healthy = createOrderFixture(
                "isolation-good", new InventoryState(1, 0, 1, 0), SCAN_NOW.minusMinutes(1));

        var result = orderTimeoutApi.processExpiredBatch(10);

        assertThat(result.scanned()).isEqualTo(2);
        assertThat(result.closed()).isEqualTo(1);
        assertThat(orderStatus(failing.orderId())).isEqualTo("WAIT_PAY");
        assertThat(inventory(failing.sessionId())).isEqualTo(new InventoryState(1, 1, 0, 0));
        assertThat(orderStatus(healthy.orderId())).isEqualTo("CLOSED");
        assertThat(inventory(healthy.sessionId())).isEqualTo(new InventoryState(1, 1, 0, 0));
    }

    @Test
    void scansOneBoundedBatchInExpirationThenIdOrder() {
        assertNoUnexpectedExpiredCandidates();
        OrderFixture a = createOrderFixture(
                "order-a", new InventoryState(1, 0, 1, 0), SCAN_NOW.minusMinutes(2));
        OrderFixture b = createOrderFixture(
                "order-b", new InventoryState(1, 0, 1, 0), SCAN_NOW.minusMinutes(3));
        OrderFixture c = createOrderFixture(
                "order-c", new InventoryState(1, 0, 1, 0), SCAN_NOW.minusMinutes(3));
        OrderFixture d = createOrderFixture(
                "order-d", new InventoryState(1, 0, 1, 0), SCAN_NOW.minusMinutes(1));
        OrderFixture e = createOrderFixture(
                "order-e", new InventoryState(1, 0, 1, 0), SCAN_NOW.minusMinutes(4));
        assertThat(b.orderId()).isLessThan(c.orderId());
        assertThat(orderMapper.findExpiredWaitPayIds(SCAN_NOW, 3))
                .containsExactly(e.orderId(), b.orderId(), c.orderId());

        var first = orderTimeoutApi.processExpiredBatch(3);

        assertThat(first.scanned()).isEqualTo(3);
        assertThat(first.closed()).isEqualTo(3);
        assertThat(orderStatus(e.orderId())).isEqualTo("CLOSED");
        assertThat(orderStatus(b.orderId())).isEqualTo("CLOSED");
        assertThat(orderStatus(c.orderId())).isEqualTo("CLOSED");
        assertThat(orderStatus(a.orderId())).isEqualTo("WAIT_PAY");
        assertThat(orderStatus(d.orderId())).isEqualTo("WAIT_PAY");

        var second = orderTimeoutApi.processExpiredBatch(3);

        assertThat(second.scanned()).isEqualTo(2);
        assertThat(second.closed()).isEqualTo(2);
        assertThat(orderStatus(a.orderId())).isEqualTo("CLOSED");
        assertThat(orderStatus(d.orderId())).isEqualTo("CLOSED");
    }

    @Test
    void concurrentTimeoutProcessorsCloseAndReleaseExactlyOnce() throws Exception {
        OrderFixture fixture = createOrderFixture(
                "timeout-race", new InventoryState(2, 1, 1, 0), SCAN_NOW.minusMinutes(1));

        List<Outcome> outcomes = runConcurrently(
                () -> orderTimeoutProcessor.closeExpiredOrder(fixture.orderId(), SCAN_NOW).isPresent()
                        ? Outcome.TIMEOUT_WON : Outcome.TIMEOUT_NOOP,
                () -> orderTimeoutProcessor.closeExpiredOrder(fixture.orderId(), SCAN_NOW).isPresent()
                        ? Outcome.TIMEOUT_WON : Outcome.TIMEOUT_NOOP);

        assertThat(outcomes).filteredOn(Outcome.TIMEOUT_WON::equals).hasSize(1);
        assertThat(outcomes).filteredOn(Outcome.TIMEOUT_NOOP::equals).hasSize(1);
        assertThat(orderStatus(fixture.orderId())).isEqualTo("CLOSED");
        assertThat(inventory(fixture.sessionId())).isEqualTo(new InventoryState(2, 2, 0, 0));
        assertInventoryInvariant(fixture.sessionId());
    }

    @Test
    void cancelAndTimeoutRaceHasOneTransitionAndOneRelease() throws Exception {
        OrderFixture fixture = createOrderFixture(
                "cancel-race", new InventoryState(2, 1, 1, 0), SCAN_NOW.minusMinutes(1));

        List<Outcome> outcomes = runConcurrently(
                () -> {
                    try {
                        orderCommandApi.cancel(fixture.userId(), fixture.orderId());
                        return Outcome.CANCEL_WON;
                    } catch (OrderException exception) {
                        if (exception.getErrorCode() == OrderErrorCode.ORDER_STATE_CONFLICT) {
                            return Outcome.ORDER_STATE_CONFLICT;
                        }
                        throw exception;
                    }
                },
                () -> orderTimeoutProcessor.closeExpiredOrder(fixture.orderId(), SCAN_NOW).isPresent()
                        ? Outcome.TIMEOUT_WON : Outcome.TIMEOUT_NOOP);

        String status = orderStatus(fixture.orderId());
        assertThat(status).isIn("CANCELLED", "CLOSED");
        if (status.equals("CANCELLED")) {
            assertThat(outcomes).containsExactlyInAnyOrder(Outcome.CANCEL_WON, Outcome.TIMEOUT_NOOP);
        } else {
            assertThat(outcomes).containsExactlyInAnyOrder(Outcome.ORDER_STATE_CONFLICT, Outcome.TIMEOUT_WON);
        }
        assertThat(inventory(fixture.sessionId())).isEqualTo(new InventoryState(2, 2, 0, 0));
        assertInventoryInvariant(fixture.sessionId());
    }

    @Test
    void payAndTimeoutBoundaryRaceHasOneTransitionAndOneInventoryEffect() throws Exception {
        OrderFixture fixture = createOrderFixture(
                "pay-race", new InventoryState(2, 1, 1, 0), RACE_EXPIRE_AT);

        List<Outcome> outcomes = runConcurrently(
                () -> controlledClock.withInstant(PAYMENT_INSTANT, () -> {
                    try {
                        orderCommandApi.pay(fixture.userId(), fixture.orderId());
                        return Outcome.PAY_WON;
                    } catch (OrderException exception) {
                        if (exception.getErrorCode() == OrderErrorCode.ORDER_STATE_CONFLICT) {
                            return Outcome.ORDER_STATE_CONFLICT;
                        }
                        throw exception;
                    }
                }),
                () -> orderTimeoutProcessor.closeExpiredOrder(fixture.orderId(), SCAN_NOW).isPresent()
                        ? Outcome.TIMEOUT_WON : Outcome.TIMEOUT_NOOP);

        String status = orderStatus(fixture.orderId());
        assertThat(status).isIn("PAID", "CLOSED");
        if (status.equals("PAID")) {
            assertThat(outcomes).containsExactlyInAnyOrder(Outcome.PAY_WON, Outcome.TIMEOUT_NOOP);
            assertThat(inventory(fixture.sessionId())).isEqualTo(new InventoryState(2, 1, 0, 1));
            assertThat(ticketCountByOrder(fixture.orderId())).isEqualTo(1);
        } else {
            assertThat(outcomes).containsExactlyInAnyOrder(Outcome.ORDER_STATE_CONFLICT, Outcome.TIMEOUT_WON);
            assertThat(inventory(fixture.sessionId())).isEqualTo(new InventoryState(2, 2, 0, 0));
            assertThat(ticketCountByOrder(fixture.orderId())).isZero();
        }
        assertInventoryInvariant(fixture.sessionId());
    }

    @AfterEach
    void cleanupAndVerifyNoSyntheticResidue() {
        jdbcTemplate.update("DELETE t FROM ticket t JOIN ticket_order o ON o.id = t.order_id "
                + "WHERE o.order_no LIKE ?", ORDER_PREFIX + "%");
        assertThat(count("SELECT COUNT(*) FROM ticket t JOIN ticket_order o ON o.id = t.order_id "
                + "WHERE o.order_no LIKE ?", ORDER_PREFIX + "%")).isZero();
        jdbcTemplate.update("DELETE FROM ticket_order WHERE order_no LIKE ?", ORDER_PREFIX + "%");
        jdbcTemplate.update("DELETE i FROM event_inventory i JOIN event_sessions s ON s.id = i.session_id "
                + "WHERE s.name LIKE ?", TEST_PREFIX + "%");
        jdbcTemplate.update("DELETE FROM event_sessions WHERE name LIKE ?", TEST_PREFIX + "%");
        jdbcTemplate.update("DELETE FROM events WHERE title LIKE ?", TEST_PREFIX + "%");
        jdbcTemplate.update("DELETE FROM users WHERE username LIKE ?", TEST_PREFIX + "%");
        assertThat(count("SELECT COUNT(*) FROM ticket_order WHERE order_no LIKE ?", ORDER_PREFIX + "%")).isZero();
        assertThat(count("SELECT COUNT(*) FROM event_inventory i JOIN event_sessions s ON s.id = i.session_id "
                + "WHERE s.name LIKE ?", TEST_PREFIX + "%")).isZero();
        assertThat(count("SELECT COUNT(*) FROM event_sessions WHERE name LIKE ?", TEST_PREFIX + "%")).isZero();
        assertThat(count("SELECT COUNT(*) FROM events WHERE title LIKE ?", TEST_PREFIX + "%")).isZero();
        assertThat(count("SELECT COUNT(*) FROM users WHERE username LIKE ?", TEST_PREFIX + "%")).isZero();
    }

    private void assertNoUnexpectedExpiredCandidates() {
        assertThat(count("SELECT COUNT(*) FROM ticket_order "
                        + "WHERE status = 'WAIT_PAY' AND expire_at <= ? AND order_no NOT LIKE ?",
                SCAN_NOW, ORDER_PREFIX + "%"))
                .as("global timeout scanner precondition")
                .isZero();
    }

    private List<Outcome> runConcurrently(Callable<Outcome> first, Callable<Outcome> second) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Outcome>> futures = new ArrayList<>();
        try {
            futures.add(executor.submit(() -> awaitStartThenCall(ready, start, first)));
            futures.add(executor.submit(() -> awaitStartThenCall(ready, start, second)));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            executor.shutdown();
            assertThat(executor.awaitTermination(CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();

            List<Outcome> outcomes = new ArrayList<>();
            for (Future<Outcome> future : futures) {
                outcomes.add(future.get(1, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private Outcome awaitStartThenCall(
            CountDownLatch ready,
            CountDownLatch start,
            Callable<Outcome> action) throws Exception {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Timeout contention start latch timed out");
        }
        return action.call();
    }

    private OrderFixture createOrderFixture(
            String scenario,
            InventoryState inventory,
            LocalDateTime expireAt) {
        String suffix = scenario + '-' + shortId();
        String username = TEST_PREFIX + suffix;
        jdbcTemplate.update("INSERT INTO users (username, password_hash, display_name, role) "
                        + "VALUES (?, ?, ?, 'USER')",
                username, ENCODED_PASSWORD, scenario);
        long userId = jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE username = ?", Long.class, username);

        String title = TEST_PREFIX + suffix;
        jdbcTemplate.update("INSERT INTO events (organizer_user_id, title, status) VALUES (?, ?, 'PUBLISHED')",
                userId, title);
        long eventId = jdbcTemplate.queryForObject("SELECT id FROM events WHERE title = ?", Long.class, title);

        String sessionName = TEST_PREFIX + suffix;
        jdbcTemplate.update("INSERT INTO event_sessions "
                        + "(event_id, name, sale_start_at, sale_end_at, start_at, end_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                eventId, sessionName, SCAN_NOW.minusDays(1), SCAN_NOW.plusDays(1),
                SCAN_NOW.plusDays(2), SCAN_NOW.plusDays(3));
        long sessionId = jdbcTemplate.queryForObject(
                "SELECT id FROM event_sessions WHERE name = ?", Long.class, sessionName);
        jdbcTemplate.update("INSERT INTO event_inventory "
                        + "(session_id, total_stock, available_stock, locked_stock, sold_stock) "
                        + "VALUES (?, ?, ?, ?, ?)",
                sessionId, inventory.total(), inventory.available(), inventory.locked(), inventory.sold());

        String orderNo = ORDER_PREFIX + UUID.randomUUID().toString().replace("-", "").toUpperCase();
        jdbcTemplate.update("INSERT INTO ticket_order "
                        + "(order_no, user_id, session_id, status, expire_at) "
                        + "VALUES (?, ?, ?, 'WAIT_PAY', ?)",
                orderNo, userId, sessionId, expireAt);
        long orderId = jdbcTemplate.queryForObject(
                "SELECT id FROM ticket_order WHERE order_no = ?", Long.class, orderNo);
        return new OrderFixture(userId, eventId, sessionId, orderId);
    }

    private String orderStatus(long orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM ticket_order WHERE id = ?", String.class, orderId);
    }

    private long ticketCountByOrder(long orderId) {
        Long value = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ticket WHERE order_id = ?", Long.class, orderId);
        return value == null ? 0 : value;
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

    private long count(String sql, Object... arguments) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, arguments);
        return value == null ? 0 : value;
    }

    private static String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private enum Outcome {
        TIMEOUT_WON,
        TIMEOUT_NOOP,
        CANCEL_WON,
        PAY_WON,
        ORDER_STATE_CONFLICT
    }

    private record OrderFixture(long userId, long eventId, long sessionId, long orderId) {
    }

    private record InventoryState(int total, int available, int locked, int sold) {
    }

    static final class ThreadAwareClock extends Clock {

        private final ThreadLocal<Instant> threadInstant = new ThreadLocal<>();

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            Instant current = threadInstant.get();
            return current == null ? SCAN_INSTANT : current;
        }

        private <T> T withInstant(Instant instant, Callable<T> action) throws Exception {
            threadInstant.set(instant);
            try {
                return action.call();
            } finally {
                threadInstant.remove();
            }
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ControlledClockConfiguration {

        @Bean
        @Primary
        ThreadAwareClock timeoutTestClock() {
            return new ThreadAwareClock();
        }
    }
}

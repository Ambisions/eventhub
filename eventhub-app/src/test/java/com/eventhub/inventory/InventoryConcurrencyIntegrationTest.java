package com.eventhub.inventory;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.eventhub.event.api.OrganizerEventApi;
import com.eventhub.event.api.command.CreateEventCommand;
import com.eventhub.event.api.command.CreateEventSessionCommand;
import com.eventhub.event.api.error.EventErrorCode;
import com.eventhub.event.api.error.EventException;
import com.eventhub.event.api.model.ManagedEventView;
import com.eventhub.inventory.api.InventoryManagementApi;
import com.eventhub.inventory.api.InventoryReservationApi;
import com.eventhub.inventory.api.command.ConfigureInventoryCommand;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class InventoryConcurrencyIntegrationTest {

    private static final String TEST_PREFIX = "phase6-inventory-race-";
    private static final long OWNER_ID = 9_601L;
    private static final long TIMEOUT_SECONDS = 10;

    @Autowired
    private OrganizerEventApi organizerEventApi;

    @Autowired
    private InventoryManagementApi inventoryManagementApi;

    @Autowired
    private InventoryReservationApi inventoryReservationApi;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void inventoryConfigurationHoldsParentEventLockUntilOuterCommit() throws Exception {
        Fixture fixture = createFixture("inventory-first");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch inventoryApplied = new CountDownLatch(1);
        CountDownLatch allowCommit = new CountDownLatch(1);
        try {
            Future<?> configure = executor.submit(() -> transactionTemplate().executeWithoutResult(status -> {
                inventoryManagementApi.configureInventory(
                        OWNER_ID, new ConfigureInventoryCommand(fixture.sessionId(), 7));
                inventoryApplied.countDown();
                await(allowCommit, "inventory commit");
            }));
            assertThat(inventoryApplied.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();

            Future<?> submit = executor.submit(() -> organizerEventApi.submitEvent(OWNER_ID, fixture.eventId()));
            assertThat(awaitWaitingSql("%UPDATE%EVENTS%SET%STATUS%PENDING_REVIEW%", submit)).isTrue();
            assertThat(submit).isNotDone();

            allowCommit.countDown();
            configure.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            submit.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            assertThat(databaseStatus(fixture.eventId())).isEqualTo("PENDING_REVIEW");
            assertThat(inventoryTotal(fixture.sessionId())).isEqualTo(7);
        } finally {
            allowCommit.countDown();
            shutdown(executor);
            cleanup(fixture);
        }
    }

    @Test
    void inventoryConfigurationIsRejectedWhenSubmitCommitsFirst() throws Exception {
        Fixture fixture = createFixture("submit-first");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch submitApplied = new CountDownLatch(1);
        CountDownLatch allowCommit = new CountDownLatch(1);
        try {
            Future<?> submit = executor.submit(() -> transactionTemplate().executeWithoutResult(status -> {
                organizerEventApi.submitEvent(OWNER_ID, fixture.eventId());
                submitApplied.countDown();
                await(allowCommit, "submit commit");
            }));
            assertThat(submitApplied.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();

            Future<EventErrorCode> configure = executor.submit(() -> {
                try {
                    inventoryManagementApi.configureInventory(
                            OWNER_ID, new ConfigureInventoryCommand(fixture.sessionId(), 7));
                    return null;
                } catch (EventException exception) {
                    return exception.getErrorCode();
                }
            });
            assertThat(awaitWaitingSql("%SELECT%FROM%EVENTS%FOR UPDATE%", configure)).isTrue();
            assertThat(configure).isNotDone();

            allowCommit.countDown();
            submit.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertThat(configure.get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    .isEqualTo(EventErrorCode.EVENT_NOT_EDITABLE);

            assertThat(databaseStatus(fixture.eventId())).isEqualTo("PENDING_REVIEW");
            assertThat(inventoryCount(fixture.sessionId())).isZero();
        } finally {
            allowCommit.countDown();
            shutdown(executor);
            cleanup(fixture);
        }
    }

    @Test
    void reservationMutationsRequireCallerOwnedTransaction() {
        Fixture fixture = createFixture("mandatory");
        try {
            inventoryManagementApi.configureInventory(
                    OWNER_ID, new ConfigureInventoryCommand(fixture.sessionId(), 1));

            assertThatThrownBy(() -> inventoryReservationApi.lockOne(fixture.sessionId()))
                    .isInstanceOf(IllegalTransactionStateException.class);
            assertThat(inventoryAvailable(fixture.sessionId())).isEqualTo(1);

            transactionTemplate().executeWithoutResult(status -> inventoryReservationApi.lockOne(fixture.sessionId()));
            assertThat(inventoryAvailable(fixture.sessionId())).isZero();
        } finally {
            cleanup(fixture);
        }
    }

    private Fixture createFixture(String scenario) {
        ManagedEventView event = organizerEventApi.createEvent(
                OWNER_ID, new CreateEventCommand(TEST_PREFIX + scenario + '-' + shortId(), null));
        SessionTimes times = sessionTimes();
        long sessionId = organizerEventApi.createSession(
                OWNER_ID, event.id(),
                new CreateEventSessionCommand(TEST_PREFIX + scenario + "-session", null,
                        times.saleStart(), times.saleEnd(), times.start(), times.end())).id();
        return new Fixture(event.id(), sessionId);
    }

    private boolean awaitWaitingSql(String pattern, Future<?> future) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (System.nanoTime() < deadline) {
            if (future.isDone()) {
                return false;
            }
            Integer count = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*)
                    FROM information_schema.processlist
                    WHERE USER = SUBSTRING_INDEX(CURRENT_USER(), '@', 1)
                      AND ID <> CONNECTION_ID()
                      AND INFO IS NOT NULL
                      AND UPPER(INFO) LIKE ?
                    """, Integer.class, pattern);
            if (count != null && count > 0) {
                return true;
            }
            Thread.onSpinWait();
        }
        return false;
    }

    private TransactionTemplate transactionTemplate() {
        return new TransactionTemplate(transactionManager);
    }

    private static void await(CountDownLatch latch, String operation) {
        try {
            if (!latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for " + operation);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(operation + " interrupted", exception);
        }
    }

    private static void shutdown(ExecutorService executor) throws InterruptedException {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
    }

    private String databaseStatus(long eventId) {
        return jdbcTemplate.queryForObject("SELECT status FROM events WHERE id = ?", String.class, eventId);
    }

    private int inventoryTotal(long sessionId) {
        Integer value = jdbcTemplate.queryForObject(
                "SELECT total_stock FROM event_inventory WHERE session_id = ?", Integer.class, sessionId);
        return value == null ? -1 : value;
    }

    private int inventoryAvailable(long sessionId) {
        Integer value = jdbcTemplate.queryForObject(
                "SELECT available_stock FROM event_inventory WHERE session_id = ?", Integer.class, sessionId);
        return value == null ? -1 : value;
    }

    private long inventoryCount(long sessionId) {
        Long value = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM event_inventory WHERE session_id = ?", Long.class, sessionId);
        return value == null ? 0 : value;
    }

    private void cleanup(Fixture fixture) {
        jdbcTemplate.update("DELETE FROM event_inventory WHERE session_id = ?", fixture.sessionId());
        jdbcTemplate.update("DELETE FROM event_sessions WHERE id = ?", fixture.sessionId());
        jdbcTemplate.update("DELETE FROM events WHERE id = ?", fixture.eventId());
    }

    private static SessionTimes sessionTimes() {
        LocalDateTime base = LocalDateTime.of(2033, 2, 1, 9, 0);
        return new SessionTimes(base, base.plusHours(1), base.plusHours(2), base.plusHours(4));
    }

    private static String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private record Fixture(long eventId, long sessionId) {
    }

    private record SessionTimes(
            LocalDateTime saleStart,
            LocalDateTime saleEnd,
            LocalDateTime start,
            LocalDateTime end) {
    }
}

package com.eventhub.event;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.eventhub.event.api.AdminEventReviewApi;
import com.eventhub.event.api.OrganizerEventApi;
import com.eventhub.event.api.command.CreateEventCommand;
import com.eventhub.event.api.command.CreateEventSessionCommand;
import com.eventhub.event.api.command.RejectEventCommand;
import com.eventhub.event.api.command.UpdateEventCommand;
import com.eventhub.event.api.command.UpdateEventSessionCommand;
import com.eventhub.event.api.error.EventErrorCode;
import com.eventhub.event.api.error.EventException;
import com.eventhub.event.api.model.EventStatus;
import com.eventhub.event.api.model.ManagedEventView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class EventConcurrencyIntegrationTest {

    private static final String TEST_PREFIX = "phase5-event-race-";
    private static final long OWNER_ID = 9_101L;
    private static final long TIMEOUT_SECONDS = 10;

    @Autowired
    private OrganizerEventApi organizerEventApi;

    @Autowired
    private AdminEventReviewApi adminEventReviewApi;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void allowsOnlyOneConcurrentSubmit() throws Exception {
        ManagedEventView event = createEvent("submit");
        try {
            List<Outcome> outcomes = race(
                    () -> organizerEventApi.submitEvent(OWNER_ID, event.id()),
                    () -> organizerEventApi.submitEvent(OWNER_ID, event.id()));

            assertThat(outcomes).filteredOn(Outcome::success).hasSize(1);
            assertThat(outcomes).filteredOn(outcome -> outcome.errorCode() == EventErrorCode.ILLEGAL_EVENT_STATE)
                    .hasSize(1);
            assertThat(databaseStatus(event.id())).isEqualTo("PENDING_REVIEW");
            assertThat(databaseReason(event.id())).isNull();
        } finally {
            cleanup(event.id());
        }
    }

    @Test
    void allowsOnlyOneConcurrentApproveOrReject() throws Exception {
        ManagedEventView event = createEvent("review");
        organizerEventApi.submitEvent(OWNER_ID, event.id());
        try {
            List<Outcome> outcomes = race(
                    () -> adminEventReviewApi.approveEvent(event.id()),
                    () -> adminEventReviewApi.rejectEvent(
                            event.id(), new RejectEventCommand("concurrent review reason")));

            assertThat(outcomes).filteredOn(Outcome::success).hasSize(1);
            assertThat(outcomes).filteredOn(outcome -> outcome.errorCode() == EventErrorCode.ILLEGAL_EVENT_STATE)
                    .hasSize(1);
            String status = databaseStatus(event.id());
            String reason = databaseReason(event.id());
            assertThat(status).isIn("PUBLISHED", "REJECTED");
            if ("PUBLISHED".equals(status)) {
                assertThat(reason).isNull();
            } else {
                assertThat(reason).isEqualTo("concurrent review reason");
            }
        } finally {
            cleanup(event.id());
        }
    }

    @Test
    void serializesConcurrentEventEditAndSubmit() throws Exception {
        ManagedEventView event = createEvent("edit-submit");
        String updatedTitle = TEST_PREFIX + "edited-" + shortId();
        try {
            List<Outcome> outcomes = race(
                    () -> organizerEventApi.updateEvent(
                            OWNER_ID, event.id(), new UpdateEventCommand(updatedTitle, "updated")),
                    () -> organizerEventApi.submitEvent(OWNER_ID, event.id()));

            assertThat(outcomes).anyMatch(Outcome::success);
            assertThat(databaseStatus(event.id())).isEqualTo("PENDING_REVIEW");
            Outcome editOutcome = outcomes.get(0);
            if (editOutcome.success()) {
                assertThat(databaseTitle(event.id())).isEqualTo(updatedTitle);
            } else {
                assertThat(editOutcome.errorCode()).isEqualTo(EventErrorCode.EVENT_NOT_EDITABLE);
                assertThat(databaseTitle(event.id())).isEqualTo(event.title());
            }
        } finally {
            cleanup(event.id());
        }
    }

    @Test
    void submitWaitsWhileSessionCreateHoldsParentRowLock() throws Exception {
        ManagedEventView event = createEvent("create-submit");
        SessionTimes times = sessionTimes();
        try {
            assertSessionMutationHoldsParentLockUntilCommit(
                    event.id(),
                    () -> organizerEventApi.createSession(
                            OWNER_ID,
                            event.id(),
                            new CreateEventSessionCommand(
                                    TEST_PREFIX + "created-session", null, null,
                                    times.saleStart(), times.saleEnd(), times.start(), times.end())));

            assertThat(databaseStatus(event.id())).isEqualTo("PENDING_REVIEW");
            assertThat(sessionCount(event.id())).isEqualTo(1);
        } finally {
            cleanup(event.id());
        }
    }

    @Test
    void sessionCreateIsRejectedAfterSubmitHasCommitted() {
        ManagedEventView event = createEvent("submit-create");
        SessionTimes times = sessionTimes();
        try {
            organizerEventApi.submitEvent(OWNER_ID, event.id());

            assertEventNotEditable(() -> organizerEventApi.createSession(
                    OWNER_ID,
                    event.id(),
                    new CreateEventSessionCommand(
                            TEST_PREFIX + "late-session", null, null,
                            times.saleStart(), times.saleEnd(), times.start(), times.end())));

            assertThat(databaseStatus(event.id())).isEqualTo("PENDING_REVIEW");
            assertThat(sessionCount(event.id())).isZero();
        } finally {
            cleanup(event.id());
        }
    }

    @Test
    void submitWaitsWhileSessionUpdateHoldsParentRowLock() throws Exception {
        ManagedEventView event = createEvent("update-submit");
        SessionTimes times = sessionTimes();
        long sessionId = organizerEventApi.createSession(
                OWNER_ID,
                event.id(),
                new CreateEventSessionCommand(
                        TEST_PREFIX + "original-session", null, null,
                        times.saleStart(), times.saleEnd(), times.start(), times.end())).id();
        String updatedName = TEST_PREFIX + "updated-session";
        try {
            assertSessionMutationHoldsParentLockUntilCommit(
                    event.id(),
                    () -> organizerEventApi.updateSession(
                            OWNER_ID,
                            sessionId,
                            new UpdateEventSessionCommand(
                                    updatedName, "Hall B", null,
                                    times.saleStart(), times.saleEnd(), times.start(), times.end())));

            assertThat(databaseStatus(event.id())).isEqualTo("PENDING_REVIEW");
            assertThat(databaseSessionName(sessionId)).isEqualTo(updatedName);
        } finally {
            cleanup(event.id());
        }
    }

    @Test
    void sessionUpdateIsRejectedAfterSubmitHasCommitted() {
        ManagedEventView event = createEvent("submit-update");
        SessionTimes times = sessionTimes();
        long sessionId = organizerEventApi.createSession(
                OWNER_ID,
                event.id(),
                new CreateEventSessionCommand(
                        TEST_PREFIX + "original-session", null, null,
                        times.saleStart(), times.saleEnd(), times.start(), times.end())).id();
        try {
            organizerEventApi.submitEvent(OWNER_ID, event.id());

            assertEventNotEditable(() -> organizerEventApi.updateSession(
                    OWNER_ID,
                    sessionId,
                    new UpdateEventSessionCommand(
                            TEST_PREFIX + "late-update", "Hall C", null,
                            times.saleStart(), times.saleEnd(), times.start(), times.end())));

            assertThat(databaseStatus(event.id())).isEqualTo("PENDING_REVIEW");
            assertThat(databaseSessionName(sessionId)).isEqualTo(TEST_PREFIX + "original-session");
        } finally {
            cleanup(event.id());
        }
    }

    private void assertSessionMutationHoldsParentLockUntilCommit(
            long eventId,
            Callable<?> sessionMutation) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch mutationApplied = new CountDownLatch(1);
        CountDownLatch allowMutationCommit = new CountDownLatch(1);
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        try {
            Future<?> mutationFuture = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
                try {
                    sessionMutation.call();
                    mutationApplied.countDown();
                    if (!allowMutationCommit.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out waiting to commit session mutation");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Session mutation coordination interrupted", exception);
                } catch (Exception exception) {
                    throw new IllegalStateException("Session mutation failed", exception);
                }
            }));

            assertThat(mutationApplied.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            Future<?> submitFuture = executor.submit(() -> organizerEventApi.submitEvent(OWNER_ID, eventId));

            assertThat(awaitBlockedSubmit(submitFuture)).isTrue();
            assertThat(submitFuture).isNotDone();

            allowMutationCommit.countDown();
            mutationFuture.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            submitFuture.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } finally {
            allowMutationCommit.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        }
    }

    private boolean awaitBlockedSubmit(Future<?> submitFuture) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (System.nanoTime() < deadline) {
            if (submitFuture.isDone()) {
                return false;
            }
            Integer waitingSubmitCount = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*)
                    FROM information_schema.processlist
                    WHERE USER = SUBSTRING_INDEX(CURRENT_USER(), '@', 1)
                      AND ID <> CONNECTION_ID()
                      AND INFO IS NOT NULL
                      AND UPPER(INFO) LIKE '%UPDATE%EVENTS%SET%STATUS%PENDING_REVIEW%'
                    """, Integer.class);
            if (waitingSubmitCount != null && waitingSubmitCount > 0) {
                return true;
            }
            Thread.onSpinWait();
        }
        return false;
    }

    private static void assertEventNotEditable(Callable<?> action) {
        try {
            action.call();
            throw new AssertionError("Expected session mutation to be rejected");
        } catch (EventException exception) {
            assertThat(exception.getErrorCode()).isEqualTo(EventErrorCode.EVENT_NOT_EDITABLE);
        } catch (Exception exception) {
            throw new AssertionError("Unexpected session mutation failure", exception);
        }
    }

    private List<Outcome> race(Callable<?> firstAction, Callable<?> secondAction) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        try {
            Future<Outcome> first = executor.submit(() -> executeAtBarrier(barrier, firstAction));
            Future<Outcome> second = executor.submit(() -> executeAtBarrier(barrier, secondAction));
            return List.of(
                    first.get(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    second.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        }
    }

    private Outcome executeAtBarrier(CyclicBarrier barrier, Callable<?> action) throws Exception {
        barrier.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        try {
            action.call();
            return Outcome.succeeded();
        } catch (EventException exception) {
            return Outcome.failed(exception.getErrorCode());
        }
    }

    private ManagedEventView createEvent(String scenario) {
        return organizerEventApi.createEvent(
                OWNER_ID,
                new CreateEventCommand(TEST_PREFIX + scenario + '-' + shortId(), "description"));
    }

    private String databaseStatus(long eventId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM events WHERE id = ?", String.class, eventId);
    }

    private String databaseReason(long eventId) {
        return jdbcTemplate.queryForObject(
                "SELECT rejection_reason FROM events WHERE id = ?", String.class, eventId);
    }

    private String databaseTitle(long eventId) {
        return jdbcTemplate.queryForObject(
                "SELECT title FROM events WHERE id = ?", String.class, eventId);
    }

    private String databaseSessionName(long sessionId) {
        return jdbcTemplate.queryForObject(
                "SELECT name FROM event_sessions WHERE id = ?", String.class, sessionId);
    }

    private long sessionCount(long eventId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM event_sessions WHERE event_id = ?", Long.class, eventId);
        return count == null ? 0 : count;
    }

    private void cleanup(long eventId) {
        jdbcTemplate.update("DELETE FROM event_sessions WHERE event_id = ?", eventId);
        jdbcTemplate.update("DELETE FROM events WHERE id = ?", eventId);
    }

    private static SessionTimes sessionTimes() {
        LocalDateTime base = LocalDateTime.of(2031, 1, 1, 9, 0);
        return new SessionTimes(base, base.plusHours(1), base.plusHours(2), base.plusHours(4));
    }

    private static String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private record Outcome(boolean success, EventErrorCode errorCode) {

        private static Outcome succeeded() {
            return new Outcome(true, null);
        }

        private static Outcome failed(EventErrorCode errorCode) {
            return new Outcome(false, errorCode);
        }
    }

    private record SessionTimes(
            LocalDateTime saleStart,
            LocalDateTime saleEnd,
            LocalDateTime start,
            LocalDateTime end) {
    }
}

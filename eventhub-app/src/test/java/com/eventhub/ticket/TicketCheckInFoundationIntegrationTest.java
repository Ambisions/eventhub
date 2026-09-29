package com.eventhub.ticket;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import com.eventhub.event.api.EventCheckInAuthorizationApi;
import com.eventhub.event.api.error.EventCheckInAuthorizationException;
import com.eventhub.event.api.error.EventCheckInAuthorizationFailure;
import com.eventhub.ticket.api.TicketCheckInCommandApi;
import com.eventhub.ticket.api.TicketCheckInQueryApi;
import com.eventhub.ticket.api.error.TicketCheckInException;
import com.eventhub.ticket.api.error.TicketCheckInFailure;
import com.eventhub.ticket.api.model.TicketCheckInResult;
import com.eventhub.ticket.api.model.TicketCheckInTarget;
import com.eventhub.ticket.api.model.TicketStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class TicketCheckInFoundationIntegrationTest {

    private static final String EVENT_PREFIX = "phase8-checkin-event-";
    private static final String SESSION_PREFIX = "phase8-checkin-session-";
    private static final long OWNER_ID = 8_100_000_001L;
    private static final long OTHER_USER_ID = 8_100_000_002L;
    private static final long TIMEOUT_SECONDS = 20L;
    private static final AtomicLong SYNTHETIC_ID = new AtomicLong(8_200_000_000L);

    @Autowired
    private EventCheckInAuthorizationApi eventCheckInAuthorizationApi;

    @Autowired
    private TicketCheckInQueryApi ticketCheckInQueryApi;

    @Autowired
    private TicketCheckInCommandApi ticketCheckInCommandApi;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private final Set<Long> eventIds = ConcurrentHashMap.newKeySet();
    private final Set<String> ticketNumbers = ConcurrentHashMap.newKeySet();

    @Test
    void authorizesOwnerAtFrozenTimeBoundariesWithoutPublishedEligibility() {
        LocalDateTime start = LocalDateTime.of(2032, 6, 1, 10, 0, 0, 123_456_000);
        LocalDateTime end = start.plusHours(2);
        long eventId = insertEvent("DRAFT");
        long sessionId = insertSession(eventId, start, end);

        assertEventFailure(
                () -> eventCheckInAuthorizationApi.requireCheckInEligibility(
                        OWNER_ID, sessionId, start.minusNanos(1_000)),
                EventCheckInAuthorizationFailure.NOT_STARTED);
        eventCheckInAuthorizationApi.requireCheckInEligibility(OWNER_ID, sessionId, start);
        eventCheckInAuthorizationApi.requireCheckInEligibility(OWNER_ID, sessionId, start.plusHours(1));
        assertEventFailure(
                () -> eventCheckInAuthorizationApi.requireCheckInEligibility(OWNER_ID, sessionId, end),
                EventCheckInAuthorizationFailure.CLOSED);
        assertEventFailure(
                () -> eventCheckInAuthorizationApi.requireCheckInEligibility(
                        OWNER_ID, sessionId, end.plusNanos(1_000)),
                EventCheckInAuthorizationFailure.CLOSED);
        assertEventFailure(
                () -> eventCheckInAuthorizationApi.requireCheckInEligibility(
                        OTHER_USER_ID, sessionId, start),
                EventCheckInAuthorizationFailure.NOT_FOUND_OR_NOT_OWNER);
        assertEventFailure(
                () -> eventCheckInAuthorizationApi.requireCheckInEligibility(
                        OWNER_ID, Long.MAX_VALUE, start),
                EventCheckInAuthorizationFailure.NOT_FOUND_OR_NOT_OWNER);
    }

    @Test
    void resolvesMinimalTargetAndEnforcesMandatoryTransactionAndSessionBinding() {
        long sessionId = nextId();
        String ticketNo = insertTicket(sessionId, "VALID");

        TicketCheckInTarget target = ticketCheckInQueryApi.resolveTarget(ticketNo);
        assertThat(target.ticketNo()).isEqualTo(ticketNo);
        assertThat(target.sessionId()).isEqualTo(sessionId);

        assertThatThrownBy(() -> ticketCheckInCommandApi.checkIn(ticketNo, sessionId))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(ticketStatus(ticketNo)).isEqualTo("VALID");

        assertTicketFailure(
                () -> checkInTransaction(ticketNo, sessionId + 1),
                TicketCheckInFailure.NOT_FOUND);
        assertThat(ticketStatus(ticketNo)).isEqualTo("VALID");

        TicketCheckInResult result = checkInTransaction(ticketNo, sessionId);
        assertThat(result.ticketNo()).isEqualTo(ticketNo);
        assertThat(result.sessionId()).isEqualTo(sessionId);
        assertThat(result.status()).isEqualTo(TicketStatus.USED);
        assertThat(ticketStatus(ticketNo)).isEqualTo("USED");
    }

    @Test
    void concurrentCheckInHasOneWinnerAndOneAlreadyUsedLoser() throws Exception {
        long sessionId = nextId();
        String ticketNo = insertTicket(sessionId, "VALID");

        List<CheckInOutcome> outcomes = runConcurrently(ticketNo, sessionId);

        assertThat(outcomes.stream().filter(CheckInOutcome::success)).hasSize(1);
        assertThat(outcomes.stream()
                .filter(outcome -> outcome.failure() == TicketCheckInFailure.ALREADY_USED))
                .hasSize(1);
        assertThat(ticketCount(ticketNo)).isEqualTo(1);
        assertThat(ticketStatus(ticketNo)).isEqualTo("USED");
    }

    @AfterEach
    void cleanupAndVerifyNoSyntheticResidue() {
        for (String ticketNo : ticketNumbers) {
            jdbcTemplate.update("DELETE FROM ticket WHERE ticket_no = ?", ticketNo);
        }
        for (Long eventId : eventIds) {
            jdbcTemplate.update("DELETE FROM event_sessions WHERE event_id = ?", eventId);
            jdbcTemplate.update("DELETE FROM events WHERE id = ?", eventId);
        }

        for (String ticketNo : ticketNumbers) {
            assertThat(ticketCount(ticketNo)).isZero();
        }
        for (Long eventId : eventIds) {
            assertThat(count("SELECT COUNT(*) FROM event_sessions WHERE event_id = ?", eventId)).isZero();
            assertThat(count("SELECT COUNT(*) FROM events WHERE id = ?", eventId)).isZero();
        }
        ticketNumbers.clear();
        eventIds.clear();
    }

    private List<CheckInOutcome> runConcurrently(String ticketNo, long sessionId) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<CheckInOutcome> first = executor.submit(
                    () -> checkInContender(ticketNo, sessionId, ready, start));
            Future<CheckInOutcome> second = executor.submit(
                    () -> checkInContender(ticketNo, sessionId, ready, start));
            assertThat(ready.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return List.of(
                    first.get(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    second.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        }
    }

    private CheckInOutcome checkInContender(
            String ticketNo,
            long sessionId,
            CountDownLatch ready,
            CountDownLatch start) throws InterruptedException {
        ready.countDown();
        if (!start.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Timed out waiting for concurrent ticket check-in");
        }
        try {
            checkInTransaction(ticketNo, sessionId);
            return CheckInOutcome.winner();
        } catch (TicketCheckInException exception) {
            return CheckInOutcome.failure(exception.getFailure());
        }
    }

    private TicketCheckInResult checkInTransaction(String ticketNo, long sessionId) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return Objects.requireNonNull(
                transaction.execute(status -> ticketCheckInCommandApi.checkIn(ticketNo, sessionId)));
    }

    private long insertEvent(String status) {
        String title = EVENT_PREFIX + UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO events (organizer_user_id, title, description, status)
                VALUES (?, ?, ?, ?)
                """, OWNER_ID, title, "check-in integration fixture", status);
        Long eventId = jdbcTemplate.queryForObject(
                "SELECT id FROM events WHERE title = ?",
                Long.class,
                title);
        long trackedId = Objects.requireNonNull(eventId);
        eventIds.add(trackedId);
        return trackedId;
    }

    private long insertSession(long eventId, LocalDateTime start, LocalDateTime end) {
        String name = SESSION_PREFIX + UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO event_sessions (
                    event_id, name, venue, sale_start_at, sale_end_at, start_at, end_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, eventId, name, "Check-in Hall", start.minusDays(2), start.minusDays(1), start, end);
        Long sessionId = jdbcTemplate.queryForObject(
                "SELECT id FROM event_sessions WHERE event_id = ? AND name = ?",
                Long.class,
                eventId,
                name);
        return Objects.requireNonNull(sessionId);
    }

    private String insertTicket(long sessionId, String status) {
        String ticketNo = "TKT-" + String.format("%032X", nextId());
        ticketNumbers.add(ticketNo);
        jdbcTemplate.update("""
                INSERT INTO ticket (ticket_no, order_id, user_id, session_id, status)
                VALUES (?, ?, ?, ?, ?)
                """, ticketNo, nextId(), nextId(), sessionId, status);
        return ticketNo;
    }

    private String ticketStatus(String ticketNo) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM ticket WHERE ticket_no = ?",
                String.class,
                ticketNo);
    }

    private int ticketCount(String ticketNo) {
        return count("SELECT COUNT(*) FROM ticket WHERE ticket_no = ?", ticketNo);
    }

    private int count(String sql, Object argument) {
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, argument);
        return count == null ? -1 : count;
    }

    private static void assertEventFailure(
            Runnable invocation,
            EventCheckInAuthorizationFailure expected) {
        assertThatThrownBy(invocation::run)
                .isInstanceOfSatisfying(EventCheckInAuthorizationException.class,
                        exception -> assertThat(exception.getFailure()).isEqualTo(expected));
    }

    private static void assertTicketFailure(Runnable invocation, TicketCheckInFailure expected) {
        assertThatThrownBy(invocation::run)
                .isInstanceOfSatisfying(TicketCheckInException.class,
                        exception -> assertThat(exception.getFailure()).isEqualTo(expected));
    }

    private static long nextId() {
        return SYNTHETIC_ID.incrementAndGet();
    }

    private record CheckInOutcome(boolean success, TicketCheckInFailure failure) {

        private static CheckInOutcome winner() {
            return new CheckInOutcome(true, null);
        }

        private static CheckInOutcome failure(TicketCheckInFailure failure) {
            return new CheckInOutcome(false, failure);
        }
    }
}

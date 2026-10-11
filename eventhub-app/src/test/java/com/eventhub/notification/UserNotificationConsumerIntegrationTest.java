package com.eventhub.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.eventhub.notification.api.event.TicketIssuedEvent;
import com.eventhub.notification.consumer.TicketIssuedNotificationConsumer;
import com.eventhub.notification.service.UserNotificationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class UserNotificationConsumerIntegrationTest {

    private static final long USER_ID = 91_401L;
    private static final Instant OCCURRED_AT = Instant.parse("2026-10-11T12:34:56.123456789Z");

    @Autowired
    private TicketIssuedNotificationConsumer consumer;

    @Autowired
    private UserNotificationService userNotificationService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final Set<String> eventIds = ConcurrentHashMap.newKeySet();
    private final Set<String> ticketNumbers = ConcurrentHashMap.newKeySet();

    @Test
    void commitsTheFrozenPersistenceProjectionWithUtcMicrosecondPrecision() {
        TicketIssuedEvent event = event(UUID.randomUUID(), USER_ID, ticketNo(), OCCURRED_AT);

        consumer.consume(event);

        NotificationFact fact = factByEventId(event.eventId().toString());
        assertThat(fact).isEqualTo(new NotificationFact(
                event.eventId().toString(),
                USER_ID,
                "TICKET_ISSUED",
                event.ticketNo(),
                LocalDateTime.parse("2026-10-11T12:34:56.123456")));
        assertThat(createdAt(event.eventId().toString())).isNotNull();
    }

    @Test
    void acceptsEquivalentEventAndBusinessIdentityDuplicates() {
        TicketIssuedEvent first = event(UUID.randomUUID(), USER_ID, ticketNo(), OCCURRED_AT);
        TicketIssuedEvent sameBusiness = event(
                UUID.randomUUID(), USER_ID, first.ticketNo(), OCCURRED_AT.plusSeconds(5));

        consumer.consume(first);
        consumer.consume(first);
        consumer.consume(sameBusiness);

        assertThat(countByTicket(first.ticketNo())).isEqualTo(1);
        assertThat(factByTicket(first.ticketNo()).eventId()).isEqualTo(first.eventId().toString());
    }

    @Test
    void rejectsConflictingEventAndBusinessIdentityDuplicates() {
        TicketIssuedEvent first = event(UUID.randomUUID(), USER_ID, ticketNo(), OCCURRED_AT);
        consumer.consume(first);

        TicketIssuedEvent conflictingEvent = event(
                first.eventId(), USER_ID + 1, first.ticketNo(), OCCURRED_AT);
        assertThatThrownBy(() -> consumer.consume(conflictingEvent))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Conflicting duplicate notification");

        TicketIssuedEvent conflictingBusiness = event(
                UUID.randomUUID(), USER_ID + 1, first.ticketNo(), OCCURRED_AT.plusSeconds(1));
        assertThatThrownBy(() -> consumer.consume(conflictingBusiness))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Conflicting duplicate notification");

        TicketIssuedEvent conflictingOccurredAt = event(
                first.eventId(), USER_ID, first.ticketNo(), OCCURRED_AT.plusSeconds(1));
        assertThatThrownBy(() -> consumer.consume(conflictingOccurredAt))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Conflicting duplicate notification");

        assertThat(countByTicket(first.ticketNo())).isEqualTo(1);
        assertThat(factByTicket(first.ticketNo()).userId()).isEqualTo(USER_ID);
        assertThat(factByTicket(first.ticketNo()).occurredAt())
                .isEqualTo(LocalDateTime.parse("2026-10-11T12:34:56.123456"));
    }

    @Test
    void rejectsCrossIdentityConflictBetweenTwoPersistedFacts() {
        TicketIssuedEvent first = event(UUID.randomUUID(), USER_ID, ticketNo(), OCCURRED_AT);
        consumer.consume(first);

        String otherTicket = ticketNo();
        TicketIssuedEvent other = event(UUID.randomUUID(), USER_ID + 1, otherTicket, OCCURRED_AT.plusSeconds(2));
        consumer.consume(other);

        TicketIssuedEvent crossConflict = event(
                first.eventId(), USER_ID + 1, other.ticketNo(), OCCURRED_AT.plusSeconds(2));
        assertThatThrownBy(() -> consumer.consume(crossConflict))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Conflicting duplicate notification");

        assertThat(countByTicket(first.ticketNo())).isEqualTo(1);
        assertThat(countByTicket(other.ticketNo())).isEqualTo(1);
    }

    @Test
    void handlesConcurrentSameEventDeliveryWithOneCommittedRow() throws Exception {
        TicketIssuedEvent event = event(UUID.randomUUID(), USER_ID, ticketNo(), OCCURRED_AT);

        runConcurrently(event, event);

        assertThat(countByTicket(event.ticketNo())).isEqualTo(1);
    }

    @Test
    void handlesConcurrentSameBusinessDeliveryWithOneCommittedRow() throws Exception {
        String ticketNo = ticketNo();
        TicketIssuedEvent first = event(UUID.randomUUID(), USER_ID, ticketNo, OCCURRED_AT);
        TicketIssuedEvent second = event(UUID.randomUUID(), USER_ID, ticketNo, OCCURRED_AT.plusSeconds(1));

        runConcurrently(first, second);

        assertThat(countByTicket(ticketNo)).isEqualTo(1);
    }

    @Test
    void rollsBackARejectedDatabaseStatementWithoutLeavingAPartialRow() {
        String tooLongTicketNo = "TKT-" + "A".repeat(61);
        TicketIssuedEvent invalidForV5 = event(UUID.randomUUID(), USER_ID, tooLongTicketNo, OCCURRED_AT);

        assertThatThrownBy(() -> consumer.consume(invalidForV5))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(countByEvent(invalidForV5.eventId().toString())).isZero();
    }

    @Test
    void rejectsARealMysqlPrimaryDuplicateAsAnUnknownConstraint() {
        long primaryKey = -Math.max(1L, UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE);
        TicketIssuedEvent persisted = event(UUID.randomUUID(), USER_ID, ticketNo(), OCCURRED_AT);
        TicketIssuedEvent attempted = event(
                UUID.randomUUID(), USER_ID, ticketNo(), OCCURRED_AT.plusSeconds(1));

        insertWithExplicitPrimaryKey(primaryKey, persisted);
        DuplicateKeyException duplicate = attemptDuplicatePrimaryKey(primaryKey, attempted);
        SQLException sqlException = findSqlException(duplicate);

        assertThat(sqlException.getErrorCode()).isEqualTo(1062);
        assertThat(sqlException.getSQLState()).isEqualTo("23000");
        assertThat(sqlException.getMessage()).containsIgnoringCase("PRIMARY");
        assertThatThrownBy(() -> userNotificationService.verifyEquivalentDuplicate(attempted, duplicate))
                .isSameAs(duplicate);
        assertThat(countByEvent(persisted.eventId().toString())).isEqualTo(1);
        assertThat(countByEvent(attempted.eventId().toString())).isZero();
    }

    @AfterEach
    void cleanupAndVerifyNoSyntheticResidue() {
        eventIds.forEach(eventId -> jdbcTemplate.update(
                "DELETE FROM user_notification WHERE event_id = ?", eventId));
        ticketNumbers.forEach(ticketNo -> jdbcTemplate.update(
                "DELETE FROM user_notification WHERE business_reference = ?", ticketNo));

        eventIds.forEach(eventId -> assertThat(countByEvent(eventId)).isZero());
        ticketNumbers.forEach(ticketNo -> assertThat(countByTicket(ticketNo)).isZero());
    }

    private void runConcurrently(TicketIssuedEvent first, TicketIssuedEvent second) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<?> firstResult = executor.submit(() -> consumeAfterBarrier(first, ready, start));
            Future<?> secondResult = executor.submit(() -> consumeAfterBarrier(second, ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            firstResult.get(10, TimeUnit.SECONDS);
            secondResult.get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private void insertWithExplicitPrimaryKey(long primaryKey, TicketIssuedEvent event) {
        jdbcTemplate.update(
                "INSERT INTO user_notification "
                        + "(id, event_id, user_id, type, business_reference, occurred_at) "
                        + "VALUES (?, ?, ?, 'TICKET_ISSUED', ?, ?)",
                primaryKey,
                event.eventId().toString(),
                event.userId(),
                event.ticketNo(),
                LocalDateTime.ofInstant(event.occurredAt(), java.time.ZoneOffset.UTC));
    }

    private DuplicateKeyException attemptDuplicatePrimaryKey(long primaryKey, TicketIssuedEvent event) {
        try {
            insertWithExplicitPrimaryKey(primaryKey, event);
        } catch (DuplicateKeyException exception) {
            return exception;
        }
        throw new AssertionError("Expected MySQL PRIMARY duplicate");
    }

    private static SQLException findSqlException(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof SQLException sqlException) {
                return sqlException;
            }
            current = current.getCause();
        }
        throw new AssertionError("Expected SQLException in duplicate exception chain", exception);
    }

    private void consumeAfterBarrier(
            TicketIssuedEvent event,
            CountDownLatch ready,
            CountDownLatch start) {
        ready.countDown();
        try {
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Concurrent consumer start barrier timed out");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Concurrent consumer interrupted", exception);
        }
        consumer.consume(event);
    }

    private TicketIssuedEvent event(UUID eventId, long userId, String ticketNo, Instant occurredAt) {
        eventIds.add(eventId.toString());
        ticketNumbers.add(ticketNo);
        return new TicketIssuedEvent(eventId, 1, userId, 92_401L, 93_401L, ticketNo, occurredAt);
    }

    private static String ticketNo() {
        return "TKT-" + UUID.randomUUID().toString().replace("-", "").toUpperCase();
    }

    private NotificationFact factByEventId(String eventId) {
        return jdbcTemplate.queryForObject(
                "SELECT event_id, user_id, type, business_reference, occurred_at "
                        + "FROM user_notification WHERE event_id = ?",
                (resultSet, rowNumber) -> new NotificationFact(
                        resultSet.getString("event_id"),
                        resultSet.getLong("user_id"),
                        resultSet.getString("type"),
                        resultSet.getString("business_reference"),
                        resultSet.getObject("occurred_at", LocalDateTime.class)),
                eventId);
    }

    private NotificationFact factByTicket(String ticketNo) {
        return jdbcTemplate.queryForObject(
                "SELECT event_id, user_id, type, business_reference, occurred_at "
                        + "FROM user_notification WHERE type = 'TICKET_ISSUED' AND business_reference = ?",
                (resultSet, rowNumber) -> new NotificationFact(
                        resultSet.getString("event_id"),
                        resultSet.getLong("user_id"),
                        resultSet.getString("type"),
                        resultSet.getString("business_reference"),
                        resultSet.getObject("occurred_at", LocalDateTime.class)),
                ticketNo);
    }

    private LocalDateTime createdAt(String eventId) {
        return jdbcTemplate.queryForObject(
                "SELECT created_at FROM user_notification WHERE event_id = ?",
                LocalDateTime.class,
                eventId);
    }

    private int countByEvent(String eventId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_notification WHERE event_id = ?", Integer.class, eventId);
        return count == null ? 0 : count;
    }

    private int countByTicket(String ticketNo) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_notification WHERE business_reference = ?",
                Integer.class,
                ticketNo);
        return count == null ? 0 : count;
    }

    private record NotificationFact(
            String eventId,
            long userId,
            String type,
            String businessReference,
            LocalDateTime occurredAt) {
    }
}

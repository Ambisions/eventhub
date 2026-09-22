package com.eventhub.ticket;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.eventhub.ticket.api.TicketIssuanceApi;
import com.eventhub.ticket.api.TicketQueryApi;
import com.eventhub.ticket.api.command.IssueTicketCommand;
import com.eventhub.ticket.api.error.TicketErrorCode;
import com.eventhub.ticket.api.error.TicketException;
import com.eventhub.ticket.api.model.TicketStatus;
import com.eventhub.ticket.api.model.TicketView;
import com.eventhub.ticket.mapper.TicketMapper;
import com.eventhub.ticket.repository.TicketEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class TicketPersistenceIntegrationTest {

    private static final long TIMEOUT_SECONDS = 20L;
    private static final AtomicLong SYNTHETIC_ID = new AtomicLong(7_000_000_000L);

    @Autowired
    private TicketIssuanceApi ticketIssuanceApi;

    @Autowired
    private TicketQueryApi ticketQueryApi;

    @Autowired
    private TicketMapper ticketMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private final Set<Long> syntheticOrderIds = ConcurrentHashMap.newKeySet();

    @Test
    void mandatoryIssuanceRequiresOuterTransactionAndPersistsDatabaseManagedFields() {
        IssueTicketCommand command = command(nextId(), nextId(), nextId());

        assertThatThrownBy(() -> ticketIssuanceApi.issueTicket(command))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(ticketCountByOrder(command.orderId())).isZero();

        TicketView issued = issueInTransaction(command);
        TicketRow persisted = ticketRowByOrder(command.orderId());

        assertThat(persisted.id()).isPositive();
        assertThat(persisted.ticketNo()).isEqualTo(issued.ticketNo());
        assertThat(persisted.status()).isEqualTo("VALID");
        assertThat(persisted.createdAt()).isNotNull().isEqualTo(issued.createdAt());
        assertThat(persisted.updatedAt()).isNotNull();
        assertThat(issued.status()).isEqualTo(TicketStatus.VALID);

        TicketView ownerView = ticketQueryApi.getTicket(command.userId(), issued.ticketNo());
        assertThat(ownerView).isEqualTo(issued);
        assertTicketError(
                () -> ticketQueryApi.getTicket(command.userId() + 1, issued.ticketNo()),
                TicketErrorCode.TICKET_NOT_FOUND);
    }

    @Test
    void ticketStatusValuesRoundTripThroughMyBatisWithoutCustomTypeHandler() {
        IssueTicketCommand validCommand = command(nextId(), nextId(), nextId());
        TicketView valid = issueInTransaction(validCommand);
        TicketEntity validEntity = ticketMapper.selectOne(
                Wrappers.<TicketEntity>lambdaQuery().eq(TicketEntity::getTicketNo, valid.ticketNo()));
        assertThat(validEntity.getStatus()).isEqualTo(TicketStatus.VALID);

        long usedOrderId = trackedOrderId();
        String usedTicketNo = syntheticTicketNo();
        insertDirect(usedTicketNo, usedOrderId, nextId(), nextId(), "USED");
        TicketEntity usedEntity = ticketMapper.selectOne(
                Wrappers.<TicketEntity>lambdaQuery().eq(TicketEntity::getTicketNo, usedTicketNo));
        assertThat(usedEntity.getStatus()).isEqualTo(TicketStatus.USED);
    }

    @Test
    void databaseEnforcesTicketNumberUniquenessAndCaseSensitiveStatusCheck() {
        String ticketNo = syntheticTicketNo();
        long firstOrderId = trackedOrderId();
        insertDirect(ticketNo, firstOrderId, nextId(), nextId(), "VALID");

        long duplicateOrderId = trackedOrderId();
        assertThatThrownBy(() -> insertDirect(ticketNo, duplicateOrderId, nextId(), nextId(), "VALID"))
                .isInstanceOfSatisfying(DataAccessException.class,
                        exception -> assertSqlConstraint(exception, "uk_ticket_ticket_no", "23000", 1062));

        long invalidStatusOrderId = trackedOrderId();
        assertThatThrownBy(() -> insertDirect(
                syntheticTicketNo(), invalidStatusOrderId, nextId(), nextId(), "valid"))
                .isInstanceOfSatisfying(DataAccessException.class,
                        exception -> assertSqlConstraint(exception, "ck_ticket_status", "HY000", 3819));
    }

    @Test
    void serviceClassifiesOrderAndUserSessionUniqueConstraints() {
        long firstOrderId = nextId();
        IssueTicketCommand first = command(firstOrderId, nextId(), nextId());
        issueInTransaction(first);

        IssueTicketCommand sameOrder = command(firstOrderId, nextId(), nextId());
        assertTicketError(() -> issueInTransaction(sameOrder), TicketErrorCode.TICKET_ALREADY_ISSUED);
        assertThat(ticketCountByOrder(firstOrderId)).isEqualTo(1);

        long sharedUserId = nextId();
        long sharedSessionId = nextId();
        IssueTicketCommand userSessionFirst = command(nextId(), sharedUserId, sharedSessionId);
        issueInTransaction(userSessionFirst);
        IssueTicketCommand userSessionDuplicate = command(nextId(), sharedUserId, sharedSessionId);
        assertTicketError(
                () -> issueInTransaction(userSessionDuplicate),
                TicketErrorCode.TICKET_ALREADY_ISSUED);
        assertThat(ticketCountByUserAndSession(sharedUserId, sharedSessionId)).isEqualTo(1);
    }

    @Test
    void concurrentIssuanceForSameOrderHasOneWinnerAndOneConflict() throws Exception {
        IssueTicketCommand command = command(nextId(), nextId(), nextId());

        List<IssueOutcome> outcomes = runConcurrently(command, command);

        assertOneWinnerOneConflict(outcomes);
        assertThat(ticketCountByOrder(command.orderId())).isEqualTo(1);
    }

    @Test
    void concurrentIssuanceForSameUserAndSessionAcrossOrdersHasOneWinnerAndOneConflict() throws Exception {
        long userId = nextId();
        long sessionId = nextId();
        IssueTicketCommand first = command(nextId(), userId, sessionId);
        IssueTicketCommand second = command(nextId(), userId, sessionId);

        List<IssueOutcome> outcomes = runConcurrently(first, second);

        assertOneWinnerOneConflict(outcomes);
        assertThat(ticketCountByUserAndSession(userId, sessionId)).isEqualTo(1);
    }

    @AfterEach
    void cleanupAndVerifyNoSyntheticTicketResidue() {
        List<Long> orderIds = new ArrayList<>(syntheticOrderIds);
        for (Long orderId : orderIds) {
            jdbcTemplate.update("DELETE FROM ticket WHERE order_id = ?", orderId);
        }
        for (Long orderId : orderIds) {
            assertThat(ticketCountByOrder(orderId)).isZero();
        }
        syntheticOrderIds.clear();
    }

    private List<IssueOutcome> runConcurrently(
            IssueTicketCommand first,
            IssueTicketCommand second) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<IssueOutcome> firstFuture = executor.submit(() -> issueContender(first, ready, start));
            Future<IssueOutcome> secondFuture = executor.submit(() -> issueContender(second, ready, start));
            assertThat(ready.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return List.of(
                    firstFuture.get(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    secondFuture.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        }
    }

    private IssueOutcome issueContender(
            IssueTicketCommand command,
            CountDownLatch ready,
            CountDownLatch start) throws InterruptedException {
        ready.countDown();
        if (!start.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Timed out waiting for concurrent ticket issuance");
        }
        try {
            issueInTransaction(command);
            return IssueOutcome.winner();
        } catch (TicketException exception) {
            return IssueOutcome.conflict(exception.getErrorCode());
        }
    }

    private TicketView issueInTransaction(IssueTicketCommand command) {
        return Objects.requireNonNull(new TransactionTemplate(transactionManager)
                .execute(status -> ticketIssuanceApi.issueTicket(command)));
    }

    private IssueTicketCommand command(long orderId, long userId, long sessionId) {
        syntheticOrderIds.add(orderId);
        return new IssueTicketCommand(orderId, userId, sessionId);
    }

    private long trackedOrderId() {
        long orderId = nextId();
        syntheticOrderIds.add(orderId);
        return orderId;
    }

    private void insertDirect(String ticketNo, long orderId, long userId, long sessionId, String status) {
        jdbcTemplate.update("""
                INSERT INTO ticket (ticket_no, order_id, user_id, session_id, status)
                VALUES (?, ?, ?, ?, ?)
                """, ticketNo, orderId, userId, sessionId, status);
    }

    private TicketRow ticketRowByOrder(long orderId) {
        return jdbcTemplate.queryForObject("""
                SELECT id, ticket_no, status, created_at, updated_at
                FROM ticket
                WHERE order_id = ?
                """, (resultSet, rowNumber) -> new TicketRow(
                resultSet.getLong("id"),
                resultSet.getString("ticket_no"),
                resultSet.getString("status"),
                resultSet.getObject("created_at", LocalDateTime.class),
                resultSet.getObject("updated_at", LocalDateTime.class)), orderId);
    }

    private int ticketCountByOrder(long orderId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ticket WHERE order_id = ?", Integer.class, orderId);
        return count == null ? -1 : count;
    }

    private int ticketCountByUserAndSession(long userId, long sessionId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ticket WHERE user_id = ? AND session_id = ?",
                Integer.class, userId, sessionId);
        return count == null ? -1 : count;
    }

    private static void assertOneWinnerOneConflict(List<IssueOutcome> outcomes) {
        assertThat(outcomes.stream().filter(IssueOutcome::success)).hasSize(1);
        assertThat(outcomes.stream()
                .filter(outcome -> outcome.errorCode() == TicketErrorCode.TICKET_ALREADY_ISSUED))
                .hasSize(1);
    }

    private static void assertTicketError(Runnable invocation, TicketErrorCode expected) {
        assertThatThrownBy(invocation::run)
                .isInstanceOfSatisfying(TicketException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(expected));
    }

    private static long nextId() {
        return SYNTHETIC_ID.incrementAndGet();
    }

    private static String syntheticTicketNo() {
        return "TKT-" + String.format("%032X", nextId());
    }

    private static void assertSqlConstraint(
            Throwable exception,
            String constraint,
            String sqlState,
            int vendorCode) {
        SQLException sqlException = findSqlException(exception);
        assertThat(sqlException.getMessage()).contains(constraint);
        assertThat(sqlException.getSQLState()).isEqualTo(sqlState);
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

    private record TicketRow(
            long id,
            String ticketNo,
            String status,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {
    }

    private record IssueOutcome(boolean success, TicketErrorCode errorCode) {

        private static IssueOutcome winner() {
            return new IssueOutcome(true, null);
        }

        private static IssueOutcome conflict(TicketErrorCode errorCode) {
            return new IssueOutcome(false, errorCode);
        }
    }
}

package com.eventhub.refund;

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

import com.eventhub.inventory.api.InventoryRefundApi;
import com.eventhub.inventory.api.error.InventoryErrorCode;
import com.eventhub.inventory.api.error.InventoryException;
import com.eventhub.ticket.api.TicketRefundApi;
import com.eventhub.ticket.api.command.RefundTicketCommand;
import com.eventhub.ticket.api.error.TicketRefundException;
import com.eventhub.ticket.api.error.TicketRefundFailure;
import com.eventhub.ticket.api.model.TicketStatus;
import com.eventhub.ticket.mapper.TicketMapper;
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
class RefundParticipantIntegrationTest {

    private static final long TIMEOUT_SECONDS = 20L;
    private static final AtomicLong SYNTHETIC_ID = new AtomicLong(9_300_000_000L);

    @Autowired
    private TicketRefundApi ticketRefundApi;

    @Autowired
    private InventoryRefundApi inventoryRefundApi;

    @Autowired
    private TicketMapper ticketMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private final Set<String> ticketNumbers = ConcurrentHashMap.newKeySet();
    private final Set<Long> inventorySessionIds = ConcurrentHashMap.newKeySet();

    @Test
    void ticketRefundRequiresOuterTransactionAndTransitionsValidTicketInsideReadCommitted() {
        TicketFixture fixture = insertTicket(nextId(), "VALID");
        RefundTicketCommand command = command(fixture);

        assertThatThrownBy(() -> ticketRefundApi.refundTicket(command))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(ticketStatus(fixture.ticketNo())).isEqualTo("VALID");

        refundTicketInTransaction(command);

        assertThat(ticketStatus(fixture.ticketNo())).isEqualTo("REFUNDED");
        assertThat(ticketMapper.selectRefundStateByOrderId(fixture.orderId()).getStatus())
                .isEqualTo(TicketStatus.REFUNDED);
    }

    @Test
    void ticketRefundClassifiesUsedRefundedMissingAndWrongBindingsWithoutMutation() {
        TicketFixture used = insertTicket(nextId(), "USED");
        assertTicketRefundFailure(() -> refundTicketInTransaction(command(used)),
                TicketRefundFailure.USED);
        assertThat(ticketStatus(used.ticketNo())).isEqualTo("USED");

        TicketFixture refunded = insertTicket(nextId(), "REFUNDED");
        assertTicketRefundFailure(() -> refundTicketInTransaction(command(refunded)),
                TicketRefundFailure.INCONSISTENT_STATE);
        assertThat(ticketStatus(refunded.ticketNo())).isEqualTo("REFUNDED");

        RefundTicketCommand missing = new RefundTicketCommand(nextId(), nextId(), nextId());
        assertTicketRefundFailure(() -> refundTicketInTransaction(missing),
                TicketRefundFailure.INCONSISTENT_STATE);

        TicketFixture valid = insertTicket(nextId(), "VALID");
        assertTicketRefundFailure(
                () -> refundTicketInTransaction(new RefundTicketCommand(
                        valid.orderId() + 1, valid.userId(), valid.sessionId())),
                TicketRefundFailure.INCONSISTENT_STATE);
        assertTicketRefundFailure(
                () -> refundTicketInTransaction(new RefundTicketCommand(
                        valid.orderId(), valid.userId() + 1, valid.sessionId())),
                TicketRefundFailure.INCONSISTENT_STATE);
        assertTicketRefundFailure(
                () -> refundTicketInTransaction(new RefundTicketCommand(
                        valid.orderId(), valid.userId(), valid.sessionId() + 1)),
                TicketRefundFailure.INCONSISTENT_STATE);
        assertThat(ticketStatus(valid.ticketNo())).isEqualTo("VALID");
    }

    @Test
    void inventoryRefundRequiresOuterTransactionAndPreservesInvariant() {
        long sessionId = insertInventory(10, 7, 0, 3);
        InventoryState before = inventory(sessionId);

        assertThatThrownBy(() -> inventoryRefundApi.refundSoldOne(sessionId))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(inventory(sessionId)).isEqualTo(before);

        refundInventoryInTransaction(sessionId);

        InventoryState after = inventory(sessionId);
        assertThat(after).isEqualTo(new InventoryState(10, 8, 0, 2));
        assertInventoryInvariant(after);
    }

    @Test
    void inventoryRefundRejectsZeroSoldAndMissingSessionWithoutMutation() {
        long sessionId = insertInventory(4, 4, 0, 0);
        InventoryState before = inventory(sessionId);

        assertInventoryFailure(() -> refundInventoryInTransaction(sessionId));
        assertThat(inventory(sessionId)).isEqualTo(before);

        assertInventoryFailure(() -> refundInventoryInTransaction(nextId()));
    }

    @Test
    void ticketAndInventoryParticipantsCommitInOneReadCommittedTransaction() {
        long sessionId = insertInventory(3, 1, 0, 2);
        TicketFixture ticket = insertTicket(sessionId, "VALID");

        readCommittedTransaction().executeWithoutResult(status -> {
            ticketRefundApi.refundTicket(command(ticket));
            inventoryRefundApi.refundSoldOne(sessionId);
        });

        assertThat(ticketStatus(ticket.ticketNo())).isEqualTo("REFUNDED");
        InventoryState inventory = inventory(sessionId);
        assertThat(inventory).isEqualTo(new InventoryState(3, 2, 0, 1));
        assertInventoryInvariant(inventory);
    }

    @Test
    void inventoryFailureRollsBackEarlierTicketRefundInSameTransaction() {
        long sessionId = insertInventory(2, 2, 0, 0);
        TicketFixture ticket = insertTicket(sessionId, "VALID");
        InventoryState before = inventory(sessionId);

        assertInventoryFailure(() -> readCommittedTransaction().executeWithoutResult(status -> {
            ticketRefundApi.refundTicket(command(ticket));
            inventoryRefundApi.refundSoldOne(sessionId);
        }));

        assertThat(ticketStatus(ticket.ticketNo())).isEqualTo("VALID");
        assertThat(inventory(sessionId)).isEqualTo(before);
        assertInventoryInvariant(before);
    }

    @Test
    void refundAndCheckInConditionalUpdatesHaveExactlyOneWinner() throws Exception {
        TicketFixture ticket = insertTicket(nextId(), "VALID");

        List<TransitionOutcome> outcomes = runRefundAndCheckInRace(ticket);

        assertThat(outcomes.stream().filter(TransitionOutcome::success)).hasSize(1);
        assertThat(outcomes.stream().filter(outcome -> !outcome.success())).hasSize(1);
        String finalStatus = ticketStatus(ticket.ticketNo());
        assertThat(finalStatus).isIn("REFUNDED", "USED");
        assertThat(ticketCount(ticket.ticketNo())).isEqualTo(1);

        if (finalStatus.equals("REFUNDED")) {
            assertThat(outcomes).anySatisfy(outcome -> {
                assertThat(outcome.operation()).isEqualTo("REFUND");
                assertThat(outcome.success()).isTrue();
            });
        } else {
            assertThat(outcomes).anySatisfy(outcome -> {
                assertThat(outcome.operation()).isEqualTo("CHECK_IN");
                assertThat(outcome.success()).isTrue();
            });
        }
    }

    @AfterEach
    void cleanupAndVerifyNoSyntheticResidue() {
        for (String ticketNo : ticketNumbers) {
            jdbcTemplate.update("DELETE FROM ticket WHERE ticket_no = ?", ticketNo);
        }
        for (Long sessionId : inventorySessionIds) {
            jdbcTemplate.update("DELETE FROM event_inventory WHERE session_id = ?", sessionId);
        }

        for (String ticketNo : ticketNumbers) {
            assertThat(ticketCount(ticketNo)).isZero();
        }
        for (Long sessionId : inventorySessionIds) {
            assertThat(count("SELECT COUNT(*) FROM event_inventory WHERE session_id = ?", sessionId))
                    .isZero();
        }
        ticketNumbers.clear();
        inventorySessionIds.clear();
    }

    private List<TransitionOutcome> runRefundAndCheckInRace(TicketFixture ticket) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<TransitionOutcome> refund = executor.submit(
                    () -> refundContender(ticket, ready, start));
            Future<TransitionOutcome> checkIn = executor.submit(
                    () -> checkInContender(ticket, ready, start));
            assertThat(ready.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return List.of(
                    refund.get(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    checkIn.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        }
    }

    private TransitionOutcome refundContender(
            TicketFixture ticket,
            CountDownLatch ready,
            CountDownLatch start) throws InterruptedException {
        try {
            return Objects.requireNonNull(readCommittedTransaction().execute(status -> {
                awaitStart(ready, start);
                ticketRefundApi.refundTicket(command(ticket));
                return TransitionOutcome.winner("REFUND");
            }));
        } catch (TicketRefundException exception) {
            if (exception.getFailure() == TicketRefundFailure.USED) {
                return TransitionOutcome.loser("REFUND");
            }
            throw exception;
        }
    }

    private TransitionOutcome checkInContender(
            TicketFixture ticket,
            CountDownLatch ready,
            CountDownLatch start) throws InterruptedException {
        return Objects.requireNonNull(readCommittedTransaction().execute(status -> {
            awaitStart(ready, start);
            int affectedRows = ticketMapper.markUsedIfValid(ticket.ticketNo(), ticket.sessionId());
            if (affectedRows == 1) {
                return TransitionOutcome.winner("CHECK_IN");
            }
            if (affectedRows == 0) {
                return TransitionOutcome.loser("CHECK_IN");
            }
            throw new IllegalStateException("Check-in affected an unexpected number of rows");
        }));
    }

    private static void awaitStart(CountDownLatch ready, CountDownLatch start) {
        ready.countDown();
        try {
            if (!start.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for refund/check-in race start");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for refund/check-in race", exception);
        }
    }

    private void refundTicketInTransaction(RefundTicketCommand command) {
        readCommittedTransaction().executeWithoutResult(status -> ticketRefundApi.refundTicket(command));
    }

    private void refundInventoryInTransaction(long sessionId) {
        readCommittedTransaction().executeWithoutResult(status -> inventoryRefundApi.refundSoldOne(sessionId));
    }

    private TransactionTemplate readCommittedTransaction() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return transaction;
    }

    private TicketFixture insertTicket(long sessionId, String status) {
        long orderId = nextId();
        long userId = nextId();
        String ticketNo = "TKT-" + String.format("%032X", nextId());
        ticketNumbers.add(ticketNo);
        jdbcTemplate.update("""
                INSERT INTO ticket (ticket_no, order_id, user_id, session_id, status)
                VALUES (?, ?, ?, ?, ?)
                """, ticketNo, orderId, userId, sessionId, status);
        return new TicketFixture(ticketNo, orderId, userId, sessionId);
    }

    private long insertInventory(int total, int available, int locked, int sold) {
        long sessionId = nextId();
        inventorySessionIds.add(sessionId);
        jdbcTemplate.update("""
                INSERT INTO event_inventory (
                    session_id, total_stock, available_stock, locked_stock, sold_stock)
                VALUES (?, ?, ?, ?, ?)
                """, sessionId, total, available, locked, sold);
        return sessionId;
    }

    private String ticketStatus(String ticketNo) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM ticket WHERE ticket_no = ?", String.class, ticketNo);
    }

    private InventoryState inventory(long sessionId) {
        return jdbcTemplate.queryForObject("""
                SELECT total_stock, available_stock, locked_stock, sold_stock
                FROM event_inventory
                WHERE session_id = ?
                """, (resultSet, rowNumber) -> new InventoryState(
                resultSet.getInt("total_stock"),
                resultSet.getInt("available_stock"),
                resultSet.getInt("locked_stock"),
                resultSet.getInt("sold_stock")), sessionId);
    }

    private int ticketCount(String ticketNo) {
        return count("SELECT COUNT(*) FROM ticket WHERE ticket_no = ?", ticketNo);
    }

    private int count(String sql, Object argument) {
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class, argument);
        return value == null ? -1 : value;
    }

    private static void assertTicketRefundFailure(Runnable invocation, TicketRefundFailure expected) {
        assertThatThrownBy(invocation::run)
                .isInstanceOfSatisfying(TicketRefundException.class,
                        exception -> assertThat(exception.getFailure()).isEqualTo(expected));
    }

    private static void assertInventoryFailure(Runnable invocation) {
        assertThatThrownBy(invocation::run)
                .isInstanceOfSatisfying(InventoryException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(InventoryErrorCode.INVENTORY_STATE_CONFLICT));
    }

    private static void assertInventoryInvariant(InventoryState inventory) {
        assertThat(inventory.total())
                .isEqualTo(inventory.available() + inventory.locked() + inventory.sold());
        assertThat(inventory.available()).isNotNegative();
        assertThat(inventory.locked()).isNotNegative();
        assertThat(inventory.sold()).isNotNegative();
    }

    private static RefundTicketCommand command(TicketFixture ticket) {
        return new RefundTicketCommand(ticket.orderId(), ticket.userId(), ticket.sessionId());
    }

    private static long nextId() {
        return SYNTHETIC_ID.incrementAndGet();
    }

    private record TicketFixture(String ticketNo, long orderId, long userId, long sessionId) {
    }

    private record InventoryState(int total, int available, int locked, int sold) {
    }

    private record TransitionOutcome(String operation, boolean success) {

        private static TransitionOutcome winner(String operation) {
            return new TransitionOutcome(operation, true);
        }

        private static TransitionOutcome loser(String operation) {
            return new TransitionOutcome(operation, false);
        }
    }
}

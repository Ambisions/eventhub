package com.eventhub.refund;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import javax.sql.DataSource;

import com.eventhub.application.checkin.CheckInApplicationService;
import com.eventhub.application.checkin.error.CheckInErrorCode;
import com.eventhub.application.checkin.error.CheckInException;
import com.eventhub.auth.api.model.AccessTokenContract;
import com.eventhub.inventory.api.InventoryRefundApi;
import com.eventhub.order.api.OrderCommandApi;
import com.eventhub.order.api.ReservationApi;
import com.eventhub.order.api.error.OrderErrorCode;
import com.eventhub.order.api.error.OrderException;
import com.eventhub.order.api.model.OrderStatus;
import com.eventhub.order.api.model.RefundResult;
import com.eventhub.ticket.api.TicketCheckInCommandApi;
import com.eventhub.ticket.api.TicketRefundApi;
import com.eventhub.ticket.api.command.RefundTicketCommand;
import com.eventhub.ticket.api.model.TicketCheckInResult;
import com.eventhub.user.api.model.UserRole;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(RefundOrchestrationIntegrationTest.RefundTestConfiguration.class)
class RefundOrchestrationIntegrationTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(RefundOrchestrationIntegrationTest.class);
    private static final long TIMEOUT_SECONDS = 20L;
    private static final Instant REFUND_INSTANT = Instant.parse("2032-06-01T10:00:00Z");
    private static final LocalDateTime REFUND_TIME = LocalDateTime.ofInstant(REFUND_INSTANT, ZoneOffset.UTC);
    private static final Set<String> REFUND_RESPONSE_FIELDS = Set.of("orderId", "status");

    @Autowired
    private OrderCommandApi orderCommandApi;

    @Autowired
    private ReservationApi reservationApi;

    @Autowired
    private CheckInApplicationService checkInApplicationService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private MutableClock refundClock;

    @Autowired
    private CoordinatedTicketRefundApi ticketRefundCoordinator;

    @Autowired
    private CountingInventoryRefundApi inventoryRefundCounter;

    @Autowired
    private CoordinatedTicketCheckInApi checkInCoordinator;

    private final List<String> ticketNumbers = new ArrayList<>();
    private final List<Long> orderIds = new ArrayList<>();
    private final List<Long> sessionIds = new ArrayList<>();
    private final List<Long> eventIds = new ArrayList<>();

    @BeforeEach
    void resetTestControls() {
        refundClock.setInstant(REFUND_INSTANT);
        ticketRefundCoordinator.reset();
        inventoryRefundCounter.reset();
        checkInCoordinator.reset();
    }

    @Test
    void sequentialRefundIsIdempotentAndPreservesHistoricalPurchaseGuard() {
        long userId = syntheticId();
        Fixture fixture = fixture(
                "sequential", userId, syntheticId(), "PAID", "VALID",
                new InventoryState(1, 0, 0, 1), REFUND_TIME.plusHours(2));

        RefundResult first = orderCommandApi.refund(userId, fixture.orderId());

        assertThat(first.status()).isEqualTo(OrderStatus.REFUNDED);
        assertRefundedState(fixture, new InventoryState(1, 1, 0, 0));
        assertThat(purchaseGuard(fixture.orderId())).isEqualTo(1);
        assertThatThrownBy(() -> reservationApi.reserve(userId, fixture.sessionId()))
                .isInstanceOfSatisfying(OrderException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(OrderErrorCode.ACTIVE_PURCHASE_EXISTS));
        assertThat(orderCount(userId, fixture.sessionId())).isEqualTo(1);

        refundClock.setInstant(REFUND_INSTANT.plusSeconds(3 * 60 * 60));
        RefundResult repeated = orderCommandApi.refund(userId, fixture.orderId());

        assertThat(repeated).isEqualTo(first);
        assertThat(ticketRefundCoordinator.calls()).isEqualTo(1);
        assertThat(inventoryRefundCounter.calls()).isEqualTo(1);
        assertRefundedState(fixture, new InventoryState(1, 1, 0, 0));
    }

    @Test
    void enforcesOwnershipStateAndExactRefundWindowPriority() {
        long ownerId = syntheticId();
        Fixture owned = fixture(
                "ownership", ownerId, syntheticId(), "PAID", "VALID",
                new InventoryState(1, 0, 0, 1), REFUND_TIME.plusHours(1));

        assertOrderFailure(() -> orderCommandApi.refund(syntheticId(), owned.orderId()),
                OrderErrorCode.ORDER_NOT_FOUND);
        assertOrderFailure(() -> orderCommandApi.refund(ownerId, -1),
                OrderErrorCode.ORDER_NOT_FOUND);
        assertThat(ticketRefundCoordinator.calls()).isZero();
        assertThat(inventoryRefundCounter.calls()).isZero();

        for (String state : List.of("WAIT_PAY", "CLOSED", "CANCELLED")) {
            Fixture invalid = fixture(
                    "state-" + state.toLowerCase(Locale.ROOT), syntheticId(), syntheticId(), state, null,
                    new InventoryState(1, 1, 0, 0), REFUND_TIME.plusHours(1));
            assertOrderFailure(
                    () -> orderCommandApi.refund(invalid.userId(), invalid.orderId()),
                    OrderErrorCode.ORDER_NOT_REFUNDABLE);
        }

        Fixture exact = fixture(
                "exact-start", syntheticId(), syntheticId(), "PAID", "VALID",
                new InventoryState(1, 0, 0, 1), REFUND_TIME);
        assertOrderFailure(() -> orderCommandApi.refund(exact.userId(), exact.orderId()),
                OrderErrorCode.ORDER_REFUND_WINDOW_CLOSED);

        Fixture after = fixture(
                "after-start", syntheticId(), syntheticId(), "PAID", "VALID",
                new InventoryState(1, 0, 0, 1), REFUND_TIME.minusNanos(1_000));
        assertOrderFailure(() -> orderCommandApi.refund(after.userId(), after.orderId()),
                OrderErrorCode.ORDER_REFUND_WINDOW_CLOSED);

        Fixture idempotentAfterStart = fixture(
                "idempotent-after-start", syntheticId(), syntheticId(), "REFUNDED", "REFUNDED",
                new InventoryState(1, 1, 0, 0), REFUND_TIME.minusHours(1));
        assertThat(orderCommandApi.refund(idempotentAfterStart.userId(), idempotentAfterStart.orderId()).status())
                .isEqualTo(OrderStatus.REFUNDED);
    }

    @Test
    void usedTicketAndInventoryFailureRollBackAllEarlierWrites() {
        Fixture used = fixture(
                "used-ticket", syntheticId(), syntheticId(), "PAID", "USED",
                new InventoryState(2, 1, 0, 1), REFUND_TIME.plusHours(1));

        assertOrderFailure(() -> orderCommandApi.refund(used.userId(), used.orderId()),
                OrderErrorCode.ORDER_REFUND_TICKET_USED);
        assertThat(orderStatus(used.orderId())).isEqualTo("PAID");
        assertThat(ticketStatus(used.ticketNo())).isEqualTo("USED");
        assertThat(inventory(used.sessionId())).isEqualTo(new InventoryState(2, 1, 0, 1));
        assertThat(inventoryRefundCounter.calls()).isZero();

        Fixture inventoryFailure = fixture(
                "inventory-failure", syntheticId(), syntheticId(), "PAID", "VALID",
                new InventoryState(1, 1, 0, 0), REFUND_TIME.plusHours(1));

        assertThatThrownBy(() -> orderCommandApi.refund(
                inventoryFailure.userId(), inventoryFailure.orderId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Inventory state is inconsistent");
        assertThat(orderStatus(inventoryFailure.orderId())).isEqualTo("PAID");
        assertThat(ticketStatus(inventoryFailure.ticketNo())).isEqualTo("VALID");
        assertThat(inventory(inventoryFailure.sessionId())).isEqualTo(new InventoryState(1, 1, 0, 0));
    }

    @Test
    void concurrentRefundHasOneTransitionWinnerAndOneIdempotentLoser() throws Exception {
        Fixture fixture = fixture(
                "concurrent-commit", syntheticId(), syntheticId(), "PAID", "VALID",
                new InventoryState(1, 0, 0, 1), REFUND_TIME.plusHours(1));
        ticketRefundCoordinator.blockFirstAfterDelegate();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch secondStarted = new CountDownLatch(1);
        try {
            Future<RefundResult> winner = executor.submit(() -> namedRefund(
                    "refund-transition-winner", fixture));
            ticketRefundCoordinator.awaitEntered();
            Future<RefundResult> loser = executor.submit(() -> {
                secondStarted.countDown();
                return namedRefund("refund-idempotent-loser", fixture);
            });
            assertThat(secondStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            ticketRefundCoordinator.release();

            assertThat(winner.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).status())
                    .isEqualTo(OrderStatus.REFUNDED);
            assertThat(loser.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).status())
                    .isEqualTo(OrderStatus.REFUNDED);
            assertThat(ticketRefundCoordinator.callers())
                    .containsExactly("refund-transition-winner");
            assertThat(inventoryRefundCounter.calls()).isEqualTo(1);
            assertRefundedState(fixture, new InventoryState(1, 1, 0, 0));
        } finally {
            ticketRefundCoordinator.release();
            shutdown(executor);
        }
    }

    @Test
    void rollbackWinnerHandsOrderRowToWaitingRefundWhichCompletes() throws Exception {
        Fixture fixture = fixture(
                "rollback-handoff", syntheticId(), syntheticId(), "PAID", "VALID",
                new InventoryState(1, 0, 0, 1), REFUND_TIME.plusHours(1));
        ticketRefundCoordinator.failFirstBeforeDelegate();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch blockerConnectionRecorded = new CountDownLatch(1);
        CountDownLatch waiterConnectionRecorded = new CountDownLatch(1);
        AtomicLong blockerConnectionId = new AtomicLong(-1);
        AtomicLong waiterConnectionId = new AtomicLong(-1);
        try (Connection observer = administrativeObserverConnection()) {
            Future<RefundResult> rolledBackWinner = executor.submit(() -> transactionalNamedRefund(
                    "rolled-back-first-winner", fixture, blockerConnectionId, blockerConnectionRecorded));
            awaitConnectionIdentity("A blocker", blockerConnectionRecorded, blockerConnectionId, rolledBackWinner);
            ticketRefundCoordinator.awaitEntered();
            Future<RefundResult> handoffWinner = executor.submit(() -> transactionalNamedRefund(
                    "handoff-transition-winner", fixture, waiterConnectionId, waiterConnectionRecorded));
            awaitConnectionIdentity("B waiter", waiterConnectionRecorded, waiterConnectionId, handoffWinner);

            LockWaitEvidence lockWait = awaitOrderRowLockWait(
                    observer,
                    fixture.orderId(),
                    blockerConnectionId.get(),
                    waiterConnectionId.get(),
                    rolledBackWinner,
                    handoffWinner);
            assertThat(lockWait.requestingConnectionId()).isEqualTo(waiterConnectionId.get());
            assertThat(lockWait.blockingConnectionId()).isEqualTo(blockerConnectionId.get());
            assertThat(lockWait.table()).isEqualTo("ticket_order");
            assertThat(lockWait.index()).isEqualTo("PRIMARY");
            assertThat(lockWait.lockType()).isEqualTo("RECORD");
            assertThat(lockWait.requestingStatus()).isEqualTo("WAITING");
            assertThat(lockWait.blockingStatus()).isEqualTo("GRANTED");
            assertLockDataMatchesOrderWhenPresent(lockWait.requestingLockData(), fixture.orderId());
            assertLockDataMatchesOrderWhenPresent(lockWait.blockingLockData(), fixture.orderId());
            LOGGER.info("refund-rollback-handoff-lock: {}", lockWait.summary());

            ticketRefundCoordinator.release();

            assertThat(failureOf(rolledBackWinner)).isInstanceOf(IllegalStateException.class);
            assertThat(handoffWinner.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).status())
                    .isEqualTo(OrderStatus.REFUNDED);
            assertThat(ticketRefundCoordinator.callers())
                    .containsExactly("rolled-back-first-winner", "handoff-transition-winner");
            assertThat(ticketRefundCoordinator.delegations()).isEqualTo(1);
            assertThat(inventoryRefundCounter.calls()).isEqualTo(1);
            assertRefundedState(fixture, new InventoryState(1, 1, 0, 0));
            LOGGER.info("refund-rollback-handoff-outcome: {}",
                    "A=ROLLED_BACK, B=POST_ROLLBACK_WINNER_COMMITTED, order=REFUNDED, "
                            + "ticket=REFUNDED, inventory=(total=1,available=1,locked=0,sold=0), "
                            + "ticketDelegations=1, inventoryRestores=1");
        } finally {
            ticketRefundCoordinator.release();
            shutdown(executor);
        }
    }

    @Test
    void refundWinningTicketRaceRejectsCheckInWithoutMixedCommittedState() throws Exception {
        long organizerId = syntheticId();
        Fixture fixture = fixture(
                "refund-wins-checkin", syntheticId(), organizerId, "PAID", "VALID",
                new InventoryState(1, 0, 0, 1), REFUND_TIME.plusMinutes(1));
        ticketRefundCoordinator.blockFirstAfterDelegate();
        checkInCoordinator.signalBeforeDelegate();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<RefundResult> refund = executor.submit(() -> namedRefund("refund-race-winner", fixture));
            ticketRefundCoordinator.awaitEntered();
            refundClock.setInstant(REFUND_INSTANT.plusSeconds(61));
            Future<TicketCheckInResult> checkIn = executor.submit(
                    () -> checkInApplicationService.checkIn(organizerId, fixture.ticketNo()));
            checkInCoordinator.awaitEntered();
            ticketRefundCoordinator.release();

            assertThat(refund.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).status())
                    .isEqualTo(OrderStatus.REFUNDED);
            assertCheckInFutureFailure(checkIn, CheckInErrorCode.TICKET_REFUNDED);
            assertRefundedState(fixture, new InventoryState(1, 1, 0, 0));
        } finally {
            ticketRefundCoordinator.release();
            checkInCoordinator.release();
            shutdown(executor);
        }
    }

    @Test
    void checkInWinningTicketRaceRollsBackRefundOrderTransition() throws Exception {
        long organizerId = syntheticId();
        Fixture fixture = fixture(
                "checkin-wins-refund", syntheticId(), organizerId, "PAID", "VALID",
                new InventoryState(1, 0, 0, 1), REFUND_TIME.plusMinutes(1));
        checkInCoordinator.blockFirstAfterDelegate();
        ticketRefundCoordinator.signalBeforeDelegate();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            refundClock.setInstant(REFUND_INSTANT.plusSeconds(61));
            Future<TicketCheckInResult> checkIn = executor.submit(
                    () -> checkInApplicationService.checkIn(organizerId, fixture.ticketNo()));
            checkInCoordinator.awaitEntered();
            refundClock.setInstant(REFUND_INSTANT);
            Future<RefundResult> refund = executor.submit(() -> namedRefund("refund-race-loser", fixture));
            ticketRefundCoordinator.awaitEntered();
            checkInCoordinator.release();

            assertThat(checkIn.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).status().name()).isEqualTo("USED");
            Throwable failure = failureOf(refund);
            assertThat(failure).isInstanceOfSatisfying(OrderException.class,
                    exception -> assertThat(exception.getErrorCode())
                            .isEqualTo(OrderErrorCode.ORDER_REFUND_TICKET_USED));
            assertThat(orderStatus(fixture.orderId())).isEqualTo("PAID");
            assertThat(ticketStatus(fixture.ticketNo())).isEqualTo("USED");
            assertThat(inventory(fixture.sessionId())).isEqualTo(new InventoryState(1, 0, 0, 1));
            assertThat(inventoryRefundCounter.calls()).isZero();
        } finally {
            ticketRefundCoordinator.release();
            checkInCoordinator.release();
            shutdown(executor);
        }
    }

    @Test
    void httpUsesRealJwtOwnerHidingRoleNeutralityAndExactResponseShape() throws Exception {
        long ownerId = syntheticId();
        Fixture owner = fixture(
                "http-owner", ownerId, syntheticId(), "PAID", "VALID",
                new InventoryState(1, 0, 0, 1), REFUND_TIME.plusHours(1));

        mockMvc.perform(post("/api/orders/{orderId}/refund", owner.orderId()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/orders/{orderId}/refund", owner.orderId())
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(syntheticId(), UserRole.USER)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
        mockMvc.perform(post("/api/orders/{orderId}/refund", syntheticId())
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(ownerId, UserRole.USER)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));

        String response = mockMvc.perform(post("/api/orders/{orderId}/refund", owner.orderId())
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(ownerId, UserRole.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(owner.orderId()))
                .andExpect(jsonPath("$.status").value("REFUNDED"))
                .andReturn().getResponse().getContentAsString();
        assertThat(responseFields(response)).isEqualTo(REFUND_RESPONSE_FIELDS);

        refundClock.setInstant(REFUND_INSTANT.plusSeconds(2 * 60 * 60));
        mockMvc.perform(post("/api/orders/{orderId}/refund", owner.orderId())
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(ownerId, UserRole.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REFUNDED"));

        long adminOwnerId = syntheticId();
        Fixture adminOwned = fixture(
                "http-admin-owner", adminOwnerId, syntheticId(), "PAID", "VALID",
                new InventoryState(1, 0, 0, 1), REFUND_TIME.plusHours(3));
        mockMvc.perform(post("/api/orders/{orderId}/refund", adminOwned.orderId())
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(adminOwnerId, UserRole.ADMIN)))
                .andExpect(status().isOk());

        Fixture otherOwned = fixture(
                "http-admin-nonowner", syntheticId(), syntheticId(), "PAID", "VALID",
                new InventoryState(1, 0, 0, 1), REFUND_TIME.plusHours(3));
        mockMvc.perform(post("/api/orders/{orderId}/refund", otherOwned.orderId())
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(adminOwnerId, UserRole.ADMIN)))
                .andExpect(status().isNotFound());

        long organizerId = syntheticId();
        Fixture organizerNotOrderOwner = fixture(
                "http-organizer-nonowner", syntheticId(), organizerId, "PAID", "VALID",
                new InventoryState(1, 0, 0, 1), REFUND_TIME.plusHours(3));
        mockMvc.perform(post("/api/orders/{orderId}/refund", organizerNotOrderOwner.orderId())
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(organizerId, UserRole.USER)))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/orders/abc/refund")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(ownerId, UserRole.USER)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void httpMapsStateWindowAndUsedTicketErrors() throws Exception {
        long userId = syntheticId();
        String token = bearerToken(userId, UserRole.USER);
        for (String state : List.of("WAIT_PAY", "CLOSED", "CANCELLED")) {
            Fixture invalid = fixture(
                    "http-state-" + state.toLowerCase(Locale.ROOT), userId, syntheticId(), state, null,
                    new InventoryState(1, 1, 0, 0), REFUND_TIME.plusHours(1));
            mockMvc.perform(post("/api/orders/{orderId}/refund", invalid.orderId())
                            .header(HttpHeaders.AUTHORIZATION, token))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("ORDER_NOT_REFUNDABLE"));
        }

        Fixture exact = fixture(
                "http-exact", userId, syntheticId(), "PAID", "VALID",
                new InventoryState(1, 0, 0, 1), REFUND_TIME);
        mockMvc.perform(post("/api/orders/{orderId}/refund", exact.orderId())
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_REFUND_WINDOW_CLOSED"));

        Fixture after = fixture(
                "http-after", userId, syntheticId(), "PAID", "VALID",
                new InventoryState(1, 0, 0, 1), REFUND_TIME.minusNanos(1_000));
        mockMvc.perform(post("/api/orders/{orderId}/refund", after.orderId())
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_REFUND_WINDOW_CLOSED"));

        Fixture used = fixture(
                "http-used", userId, syntheticId(), "PAID", "USED",
                new InventoryState(1, 0, 0, 1), REFUND_TIME.plusHours(1));
        mockMvc.perform(post("/api/orders/{orderId}/refund", used.orderId())
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_REFUND_TICKET_USED"));
        assertThat(orderStatus(used.orderId())).isEqualTo("PAID");
    }

    @AfterEach
    void cleanupAndVerifySyntheticResidue() {
        ticketRefundCoordinator.reset();
        checkInCoordinator.reset();
        for (String ticketNo : ticketNumbers) {
            jdbcTemplate.update("DELETE FROM ticket WHERE ticket_no = ?", ticketNo);
        }
        for (Long orderId : orderIds) {
            jdbcTemplate.update("DELETE FROM ticket_order WHERE id = ?", orderId);
        }
        for (Long sessionId : sessionIds) {
            jdbcTemplate.update("DELETE FROM event_inventory WHERE session_id = ?", sessionId);
            jdbcTemplate.update("DELETE FROM event_sessions WHERE id = ?", sessionId);
        }
        for (Long eventId : eventIds) {
            jdbcTemplate.update("DELETE FROM events WHERE id = ?", eventId);
        }

        assertThat(ticketNumbers).allSatisfy(ticketNo -> assertThat(count(
                "SELECT COUNT(*) FROM ticket WHERE ticket_no = ?", ticketNo)).isZero());
        assertThat(orderIds).allSatisfy(orderId -> assertThat(count(
                "SELECT COUNT(*) FROM ticket_order WHERE id = ?", orderId)).isZero());
        assertThat(sessionIds).allSatisfy(sessionId -> assertThat(count(
                "SELECT COUNT(*) FROM event_sessions WHERE id = ?", sessionId)).isZero());
        assertThat(eventIds).allSatisfy(eventId -> assertThat(count(
                "SELECT COUNT(*) FROM events WHERE id = ?", eventId)).isZero());
        ticketNumbers.clear();
        orderIds.clear();
        sessionIds.clear();
        eventIds.clear();
    }

    private RefundResult namedRefund(String threadName, Fixture fixture) {
        Thread.currentThread().setName(threadName);
        return orderCommandApi.refund(fixture.userId(), fixture.orderId());
    }

    private RefundResult transactionalNamedRefund(
            String threadName,
            Fixture fixture,
            AtomicLong connectionId,
            CountDownLatch connectionRecorded) {
        Thread.currentThread().setName(threadName);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setName(threadName);
        RefundResult result = transaction.execute(status -> {
            Long currentConnectionId = jdbcTemplate.queryForObject("SELECT CONNECTION_ID()", Long.class);
            if (currentConnectionId == null || currentConnectionId <= 0) {
                throw new IllegalStateException("MySQL did not expose a valid transaction connection id");
            }
            connectionId.set(currentConnectionId);
            connectionRecorded.countDown();
            return orderCommandApi.refund(fixture.userId(), fixture.orderId());
        });
        if (result == null) {
            throw new IllegalStateException("Refund transaction returned no result");
        }
        return result;
    }

    private Connection administrativeObserverConnection() throws SQLException {
        String rootPassword = System.getenv("MYSQL_ROOT_PASSWORD");
        if (rootPassword == null || rootPassword.isBlank()) {
            throw new IllegalStateException(
                    "MYSQL_ROOT_PASSWORD must be present for read-only Performance Schema verification");
        }
        String jdbcUrl;
        try (Connection applicationConnection = dataSource.getConnection()) {
            jdbcUrl = applicationConnection.getMetaData().getURL();
        }
        Connection observer = DriverManager.getConnection(jdbcUrl, "root", rootPassword);
        observer.setReadOnly(true);
        return observer;
    }

    private static void awaitConnectionIdentity(
            String worker,
            CountDownLatch recorded,
            AtomicLong connectionId,
            Future<?> future) throws InterruptedException {
        assertThat(recorded.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                .as("%s must record CONNECTION_ID() before the refund call; future=%s",
                        worker, futureState(future))
                .isTrue();
        assertThat(connectionId.get()).as("%s MySQL connection id", worker).isPositive();
    }

    private static LockWaitEvidence awaitOrderRowLockWait(
            Connection observer,
            long orderId,
            long blockerConnectionId,
            long waiterConnectionId,
            Future<?> blockerFuture,
            Future<?> waiterFuture) throws Exception {
        String schema = currentSchema(observer);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            LockWaitEvidence evidence = findOrderRowLockWait(
                    observer, schema, blockerConnectionId, waiterConnectionId);
            if (evidence != null) {
                return evidence;
            }
            TimeUnit.MILLISECONDS.sleep(25);
        }
        throw new AssertionError("Timed out waiting for B to wait on A's ticket_order PRIMARY record lock. "
                + "A=" + blockerConnectionId
                + ", B=" + waiterConnectionId
                + ", orderId=" + orderId
                + ", schema=" + schema
                + ", A future=" + futureState(blockerFuture)
                + ", B future=" + futureState(waiterFuture)
                + ", data_lock_waits=" + diagnosticLockWaits(observer, blockerConnectionId, waiterConnectionId)
                + ", data_locks=" + diagnosticLocks(observer, blockerConnectionId, waiterConnectionId)
                + ", threads=" + diagnosticThreads(observer, blockerConnectionId, waiterConnectionId)
                + ", innodb_trx=" + diagnosticTransactions(observer, blockerConnectionId, waiterConnectionId));
    }

    private static LockWaitEvidence findOrderRowLockWait(
            Connection observer,
            String schema,
            long blockerConnectionId,
            long waiterConnectionId) throws SQLException {
        try (PreparedStatement statement = observer.prepareStatement("""
                SELECT
                    requesting_thread.PROCESSLIST_ID AS requesting_connection_id,
                    blocking_thread.PROCESSLIST_ID AS blocking_connection_id,
                    requesting_lock.OBJECT_SCHEMA,
                    requesting_lock.OBJECT_NAME,
                    requesting_lock.INDEX_NAME,
                    requesting_lock.LOCK_TYPE,
                    requesting_lock.LOCK_STATUS AS requesting_status,
                    blocking_lock.LOCK_STATUS AS blocking_status,
                    requesting_lock.LOCK_DATA AS requesting_lock_data,
                    blocking_lock.LOCK_DATA AS blocking_lock_data
                FROM performance_schema.data_lock_waits lock_wait
                JOIN performance_schema.data_locks requesting_lock
                  ON requesting_lock.ENGINE = lock_wait.ENGINE
                 AND requesting_lock.ENGINE_LOCK_ID = lock_wait.REQUESTING_ENGINE_LOCK_ID
                JOIN performance_schema.data_locks blocking_lock
                  ON blocking_lock.ENGINE = lock_wait.ENGINE
                 AND blocking_lock.ENGINE_LOCK_ID = lock_wait.BLOCKING_ENGINE_LOCK_ID
                JOIN performance_schema.threads requesting_thread
                  ON requesting_thread.THREAD_ID = lock_wait.REQUESTING_THREAD_ID
                JOIN performance_schema.threads blocking_thread
                  ON blocking_thread.THREAD_ID = lock_wait.BLOCKING_THREAD_ID
                WHERE requesting_thread.PROCESSLIST_ID = ?
                  AND blocking_thread.PROCESSLIST_ID = ?
                  AND requesting_lock.OBJECT_SCHEMA = ?
                  AND requesting_lock.OBJECT_NAME = 'ticket_order'
                  AND requesting_lock.INDEX_NAME = 'PRIMARY'
                  AND requesting_lock.LOCK_TYPE = 'RECORD'
                  AND requesting_lock.LOCK_STATUS = 'WAITING'
                  AND blocking_lock.OBJECT_SCHEMA = requesting_lock.OBJECT_SCHEMA
                  AND blocking_lock.OBJECT_NAME = requesting_lock.OBJECT_NAME
                  AND blocking_lock.INDEX_NAME = requesting_lock.INDEX_NAME
                  AND blocking_lock.LOCK_TYPE = 'RECORD'
                  AND blocking_lock.LOCK_STATUS = 'GRANTED'
                """)) {
            statement.setLong(1, waiterConnectionId);
            statement.setLong(2, blockerConnectionId);
            statement.setString(3, schema);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                return new LockWaitEvidence(
                        resultSet.getLong("requesting_connection_id"),
                        resultSet.getLong("blocking_connection_id"),
                        resultSet.getString("OBJECT_SCHEMA"),
                        resultSet.getString("OBJECT_NAME"),
                        resultSet.getString("INDEX_NAME"),
                        resultSet.getString("LOCK_TYPE"),
                        resultSet.getString("requesting_status"),
                        resultSet.getString("blocking_status"),
                        resultSet.getString("requesting_lock_data"),
                        resultSet.getString("blocking_lock_data"));
            }
        }
    }

    private static String currentSchema(Connection observer) throws SQLException {
        try (PreparedStatement statement = observer.prepareStatement("SELECT DATABASE()");
             ResultSet resultSet = statement.executeQuery()) {
            if (!resultSet.next() || resultSet.getString(1) == null) {
                throw new IllegalStateException("Administrative observer is not connected to a database");
            }
            return resultSet.getString(1);
        }
    }

    private static void assertLockDataMatchesOrderWhenPresent(String lockData, long orderId) {
        if (lockData != null) {
            assertThat(lockData.trim()).isEqualTo(Long.toString(orderId));
        }
    }

    private static String diagnosticLockWaits(
            Connection observer, long blockerConnectionId, long waiterConnectionId) {
        return diagnosticRows(observer, """
                SELECT requesting_thread.PROCESSLIST_ID AS requester,
                       blocking_thread.PROCESSLIST_ID AS blocker,
                       lock_wait.REQUESTING_ENGINE_LOCK_ID,
                       lock_wait.BLOCKING_ENGINE_LOCK_ID
                FROM performance_schema.data_lock_waits lock_wait
                JOIN performance_schema.threads requesting_thread
                  ON requesting_thread.THREAD_ID = lock_wait.REQUESTING_THREAD_ID
                JOIN performance_schema.threads blocking_thread
                  ON blocking_thread.THREAD_ID = lock_wait.BLOCKING_THREAD_ID
                WHERE requesting_thread.PROCESSLIST_ID IN (?, ?)
                   OR blocking_thread.PROCESSLIST_ID IN (?, ?)
                """, waiterConnectionId, blockerConnectionId, waiterConnectionId, blockerConnectionId);
    }

    private static String diagnosticLocks(
            Connection observer, long blockerConnectionId, long waiterConnectionId) {
        return diagnosticRows(observer, """
                SELECT thread.PROCESSLIST_ID AS connection_id,
                       data_lock.OBJECT_SCHEMA,
                       data_lock.OBJECT_NAME,
                       data_lock.INDEX_NAME,
                       data_lock.LOCK_TYPE,
                       data_lock.LOCK_MODE,
                       data_lock.LOCK_STATUS,
                       data_lock.LOCK_DATA
                FROM performance_schema.data_locks data_lock
                JOIN performance_schema.threads thread
                  ON thread.THREAD_ID = data_lock.THREAD_ID
                WHERE thread.PROCESSLIST_ID IN (?, ?)
                """, blockerConnectionId, waiterConnectionId);
    }

    private static String diagnosticThreads(
            Connection observer, long blockerConnectionId, long waiterConnectionId) {
        return diagnosticRows(observer, """
                SELECT PROCESSLIST_ID, PROCESSLIST_USER, PROCESSLIST_HOST,
                       PROCESSLIST_DB, PROCESSLIST_COMMAND, PROCESSLIST_STATE
                FROM performance_schema.threads
                WHERE PROCESSLIST_ID IN (?, ?)
                """, blockerConnectionId, waiterConnectionId);
    }

    private static String diagnosticTransactions(
            Connection observer, long blockerConnectionId, long waiterConnectionId) {
        return diagnosticRows(observer, """
                SELECT TRX_MYSQL_THREAD_ID, TRX_STATE, TRX_STARTED, TRX_WAIT_STARTED,
                       TRX_ROWS_LOCKED, TRX_ROWS_MODIFIED
                FROM information_schema.innodb_trx
                WHERE TRX_MYSQL_THREAD_ID IN (?, ?)
                """, blockerConnectionId, waiterConnectionId);
    }

    private static String diagnosticRows(Connection connection, String sql, Object... parameters) {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(2);
            for (int index = 0; index < parameters.length; index++) {
                statement.setObject(index + 1, parameters[index]);
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                ResultSetMetaData metadata = resultSet.getMetaData();
                StringBuilder rows = new StringBuilder("[");
                int rowCount = 0;
                while (resultSet.next() && rowCount < 20) {
                    if (rowCount > 0) {
                        rows.append(", ");
                    }
                    rows.append('{');
                    for (int column = 1; column <= metadata.getColumnCount(); column++) {
                        if (column > 1) {
                            rows.append(", ");
                        }
                        rows.append(metadata.getColumnLabel(column))
                                .append('=')
                                .append(resultSet.getObject(column));
                    }
                    rows.append('}');
                    rowCount++;
                }
                return rows.append(']').toString();
            }
        } catch (SQLException exception) {
            return "UNAVAILABLE(" + exception.getClass().getSimpleName() + ": " + exception.getMessage() + ')';
        }
    }

    private static String futureState(Future<?> future) {
        if (future.isCancelled()) {
            return "CANCELLED";
        }
        if (!future.isDone()) {
            return "RUNNING";
        }
        try {
            future.get(0, TimeUnit.MILLISECONDS);
            return "COMPLETED";
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            return "FAILED(" + cause.getClass().getSimpleName() + ": " + cause.getMessage() + ')';
        } catch (Exception exception) {
            return "UNAVAILABLE(" + exception.getClass().getSimpleName() + ')';
        }
    }

    private Fixture fixture(
            String scenario,
            long userId,
            long organizerId,
            String orderStatus,
            String ticketStatus,
            InventoryState inventory,
            LocalDateTime startAt) {
        String suffix = scenario + '-' + shortId();
        jdbcTemplate.update("INSERT INTO events (organizer_user_id, title, status) VALUES (?, ?, 'PUBLISHED')",
                organizerId, "phase9-refund-" + suffix);
        long eventId = jdbcTemplate.queryForObject(
                "SELECT id FROM events WHERE title = ?", Long.class, "phase9-refund-" + suffix);
        eventIds.add(eventId);

        jdbcTemplate.update("""
                INSERT INTO event_sessions (
                    event_id, name, sale_start_at, sale_end_at, start_at, end_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, eventId, "phase9-refund-session-" + suffix,
                REFUND_TIME.minusHours(1), REFUND_TIME.plusHours(1), startAt, startAt.plusHours(1));
        long sessionId = jdbcTemplate.queryForObject(
                "SELECT id FROM event_sessions WHERE name = ?",
                Long.class, "phase9-refund-session-" + suffix);
        sessionIds.add(sessionId);

        jdbcTemplate.update("""
                INSERT INTO event_inventory (
                    session_id, total_stock, available_stock, locked_stock, sold_stock)
                VALUES (?, ?, ?, ?, ?)
                """, sessionId, inventory.total(), inventory.available(), inventory.locked(), inventory.sold());

        String orderNo = "REFUND-" + UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
        jdbcTemplate.update("""
                INSERT INTO ticket_order (order_no, user_id, session_id, status, expire_at)
                VALUES (?, ?, ?, ?, ?)
                """, orderNo, userId, sessionId, orderStatus, REFUND_TIME.plusMinutes(15));
        long orderId = jdbcTemplate.queryForObject(
                "SELECT id FROM ticket_order WHERE order_no = ?", Long.class, orderNo);
        orderIds.add(orderId);

        String ticketNo = null;
        if (ticketStatus != null) {
            ticketNo = "TKT-" + UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
            ticketNumbers.add(ticketNo);
            jdbcTemplate.update("""
                    INSERT INTO ticket (ticket_no, order_id, user_id, session_id, status)
                    VALUES (?, ?, ?, ?, ?)
                    """, ticketNo, orderId, userId, sessionId, ticketStatus);
        }
        return new Fixture(eventId, sessionId, orderId, userId, ticketNo);
    }

    private void assertRefundedState(Fixture fixture, InventoryState expectedInventory) {
        assertThat(orderStatus(fixture.orderId())).isEqualTo("REFUNDED");
        assertThat(ticketStatus(fixture.ticketNo())).isEqualTo("REFUNDED");
        assertThat(inventory(fixture.sessionId())).isEqualTo(expectedInventory);
        assertInventoryInvariant(expectedInventory);
    }

    private static void assertCheckInFutureFailure(
            Future<TicketCheckInResult> future,
            CheckInErrorCode expected) throws Exception {
        Throwable failure = failureOf(future);
        assertThat(failure).isInstanceOfSatisfying(CheckInException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(expected));
    }

    private static void assertOrderFailure(ThrowingCall call, OrderErrorCode expected) {
        assertThatThrownBy(call::execute)
                .isInstanceOfSatisfying(OrderException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(expected));
    }

    private static Throwable failureOf(Future<?> future) throws Exception {
        try {
            future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            throw new AssertionError("Expected concurrent invocation to fail");
        } catch (ExecutionException exception) {
            return exception.getCause();
        }
    }

    private String bearerToken(long userId, UserRole role) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(AccessTokenContract.ISSUER)
                .subject(Long.toString(userId))
                .issuedAt(now.minusSeconds(1))
                .expiresAt(now.plus(AccessTokenContract.TIME_TO_LIVE))
                .claim(AccessTokenContract.USERNAME_CLAIM, "phase9-refund-" + userId)
                .claim(AccessTokenContract.ROLE_CLAIM, role.name())
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).type("JWT").build();
        return "Bearer " + jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    private Set<String> responseFields(String response) throws Exception {
        JsonNode body = objectMapper.readTree(response);
        Set<String> fields = new HashSet<>();
        body.fieldNames().forEachRemaining(fields::add);
        return fields;
    }

    private String orderStatus(long orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM ticket_order WHERE id = ?", String.class, orderId);
    }

    private int purchaseGuard(long orderId) {
        Integer guard = jdbcTemplate.queryForObject(
                "SELECT purchase_guard FROM ticket_order WHERE id = ?", Integer.class, orderId);
        return guard == null ? -1 : guard;
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

    private long orderCount(long userId, long sessionId) {
        Long value = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ticket_order WHERE user_id = ? AND session_id = ?",
                Long.class, userId, sessionId);
        return value == null ? -1 : value;
    }

    private long count(String sql, Object value) {
        Long result = jdbcTemplate.queryForObject(sql, Long.class, value);
        return result == null ? -1 : result;
    }

    private static void assertInventoryInvariant(InventoryState state) {
        assertThat(state.total()).isEqualTo(state.available() + state.locked() + state.sold());
        assertThat(state.available()).isNotNegative();
        assertThat(state.locked()).isNotNegative();
        assertThat(state.sold()).isNotNegative();
    }

    private static void shutdown(ExecutorService executor) throws InterruptedException {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
    }

    private static long syntheticId() {
        return Math.abs(UUID.randomUUID().getMostSignificantBits() >>> 1) + 1;
    }

    private static String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    @FunctionalInterface
    private interface ThrowingCall {
        void execute() throws Exception;
    }

    private record Fixture(long eventId, long sessionId, long orderId, long userId, String ticketNo) {
    }

    private record InventoryState(int total, int available, int locked, int sold) {
    }

    private record LockWaitEvidence(
            long requestingConnectionId,
            long blockingConnectionId,
            String schema,
            String table,
            String index,
            String lockType,
            String requestingStatus,
            String blockingStatus,
            String requestingLockData,
            String blockingLockData) {

        private String summary() {
            return "requester=" + requestingConnectionId
                    + ", blocker=" + blockingConnectionId
                    + ", schema=" + schema
                    + ", table=" + table
                    + ", index=" + index
                    + ", lockType=" + lockType
                    + ", requesterStatus=" + requestingStatus
                    + ", blockerStatus=" + blockingStatus
                    + ", requestingLockData=" + requestingLockData
                    + ", blockingLockData=" + blockingLockData;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class RefundTestConfiguration {

        @Bean
        @Primary
        MutableClock refundTestClock() {
            return new MutableClock(REFUND_INSTANT);
        }

        @Bean
        @Primary
        CoordinatedTicketRefundApi coordinatedTicketRefundApi(
                @Qualifier("ticketService") TicketRefundApi delegate) {
            return new CoordinatedTicketRefundApi(delegate);
        }

        @Bean
        @Primary
        CountingInventoryRefundApi countingInventoryRefundApi(
                @Qualifier("inventoryService") InventoryRefundApi delegate) {
            return new CountingInventoryRefundApi(delegate);
        }

        @Bean
        @Primary
        CoordinatedTicketCheckInApi coordinatedTicketCheckInApi(
                @Qualifier("ticketService") TicketCheckInCommandApi delegate) {
            return new CoordinatedTicketCheckInApi(delegate);
        }
    }

    static final class MutableClock extends Clock {

        private final AtomicReference<Instant> current;

        private MutableClock(Instant initial) {
            this.current = new AtomicReference<>(initial);
        }

        void setInstant(Instant instant) {
            current.set(instant);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return zone.equals(ZoneOffset.UTC) ? this : Clock.fixed(instant(), zone);
        }

        @Override
        public Instant instant() {
            return current.get();
        }
    }

    static final class CountingInventoryRefundApi implements InventoryRefundApi {

        private final InventoryRefundApi delegate;
        private final AtomicInteger calls = new AtomicInteger();

        private CountingInventoryRefundApi(InventoryRefundApi delegate) {
            this.delegate = delegate;
        }

        @Override
        public void refundSoldOne(long sessionId) {
            calls.incrementAndGet();
            delegate.refundSoldOne(sessionId);
        }

        int calls() {
            return calls.get();
        }

        void reset() {
            calls.set(0);
        }
    }

    static final class CoordinatedTicketRefundApi implements TicketRefundApi {

        private enum Mode {
            NORMAL,
            BLOCK_FIRST_AFTER_DELEGATE,
            FAIL_FIRST_BEFORE_DELEGATE,
            SIGNAL_BEFORE_DELEGATE
        }

        private final TicketRefundApi delegate;
        private final AtomicReference<Mode> mode = new AtomicReference<>(Mode.NORMAL);
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicInteger delegations = new AtomicInteger();
        private final ConcurrentLinkedQueue<String> callers = new ConcurrentLinkedQueue<>();
        private volatile CountDownLatch entered = new CountDownLatch(1);
        private volatile CountDownLatch release = new CountDownLatch(1);

        private CoordinatedTicketRefundApi(TicketRefundApi delegate) {
            this.delegate = delegate;
        }

        @Override
        public void refundTicket(RefundTicketCommand command) {
            int invocation = calls.incrementAndGet();
            callers.add(Thread.currentThread().getName());
            Mode currentMode = mode.get();
            if (currentMode == Mode.FAIL_FIRST_BEFORE_DELEGATE && invocation == 1) {
                entered.countDown();
                await(release);
                throw new IllegalStateException("synthetic first refund participant failure");
            }
            if (currentMode == Mode.SIGNAL_BEFORE_DELEGATE) {
                entered.countDown();
            }
            delegate.refundTicket(command);
            delegations.incrementAndGet();
            if (currentMode == Mode.BLOCK_FIRST_AFTER_DELEGATE && invocation == 1) {
                entered.countDown();
                await(release);
            }
        }

        void blockFirstAfterDelegate() {
            configure(Mode.BLOCK_FIRST_AFTER_DELEGATE);
        }

        void failFirstBeforeDelegate() {
            configure(Mode.FAIL_FIRST_BEFORE_DELEGATE);
        }

        void signalBeforeDelegate() {
            configure(Mode.SIGNAL_BEFORE_DELEGATE);
        }

        void awaitEntered() throws InterruptedException {
            assertThat(entered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        }

        void release() {
            release.countDown();
        }

        int calls() {
            return calls.get();
        }

        int delegations() {
            return delegations.get();
        }

        List<String> callers() {
            return List.copyOf(callers);
        }

        void reset() {
            release.countDown();
            mode.set(Mode.NORMAL);
            calls.set(0);
            delegations.set(0);
            callers.clear();
            entered = new CountDownLatch(1);
            release = new CountDownLatch(1);
        }

        private void configure(Mode nextMode) {
            reset();
            mode.set(nextMode);
        }
    }

    static final class CoordinatedTicketCheckInApi implements TicketCheckInCommandApi {

        private enum Mode {
            NORMAL,
            SIGNAL_BEFORE_DELEGATE,
            BLOCK_FIRST_AFTER_DELEGATE
        }

        private final TicketCheckInCommandApi delegate;
        private final AtomicReference<Mode> mode = new AtomicReference<>(Mode.NORMAL);
        private final AtomicInteger calls = new AtomicInteger();
        private volatile CountDownLatch entered = new CountDownLatch(1);
        private volatile CountDownLatch release = new CountDownLatch(1);

        private CoordinatedTicketCheckInApi(TicketCheckInCommandApi delegate) {
            this.delegate = delegate;
        }

        @Override
        public TicketCheckInResult checkIn(String ticketNo, long sessionId) {
            int invocation = calls.incrementAndGet();
            Mode currentMode = mode.get();
            if (currentMode == Mode.SIGNAL_BEFORE_DELEGATE) {
                entered.countDown();
            }
            TicketCheckInResult result = delegate.checkIn(ticketNo, sessionId);
            if (currentMode == Mode.BLOCK_FIRST_AFTER_DELEGATE && invocation == 1) {
                entered.countDown();
                await(release);
            }
            return result;
        }

        void signalBeforeDelegate() {
            configure(Mode.SIGNAL_BEFORE_DELEGATE);
        }

        void blockFirstAfterDelegate() {
            configure(Mode.BLOCK_FIRST_AFTER_DELEGATE);
        }

        void awaitEntered() throws InterruptedException {
            assertThat(entered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        }

        void release() {
            release.countDown();
        }

        void reset() {
            release.countDown();
            mode.set(Mode.NORMAL);
            calls.set(0);
            entered = new CountDownLatch(1);
            release = new CountDownLatch(1);
        }

        private void configure(Mode nextMode) {
            reset();
            mode.set(nextMode);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for test coordination");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted during test coordination", exception);
        }
    }
}

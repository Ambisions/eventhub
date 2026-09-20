package com.eventhub.order;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.eventhub.auth.api.AuthApi;
import com.eventhub.auth.api.command.LoginCommand;
import com.eventhub.auth.api.command.RegisterCommand;
import com.eventhub.auth.api.model.AccessToken;
import com.eventhub.inventory.api.error.InventoryErrorCode;
import com.eventhub.inventory.api.error.InventoryException;
import com.eventhub.order.api.OrderCommandApi;
import com.eventhub.order.api.OrderQueryApi;
import com.eventhub.order.api.error.OrderErrorCode;
import com.eventhub.order.api.error.OrderException;
import com.eventhub.order.api.model.OrderStatus;
import com.eventhub.user.api.model.UserProfile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(OrderLifecycleIntegrationTest.FixedClockConfiguration.class)
class OrderLifecycleIntegrationTest {

    private static final String TEST_PREFIX = "phase6-order-lifecycle-";
    private static final String PASSWORD = "valid-password";
    private static final String ENCODED_PASSWORD = "{bcrypt}$2a$10$phase6.synthetic.credential";
    private static final Instant FIXED_INSTANT = Instant.now().truncatedTo(ChronoUnit.MICROS);
    private static final LocalDateTime PAYMENT_NOW = LocalDateTime.ofInstant(FIXED_INSTANT, ZoneOffset.UTC);

    @Autowired
    private OrderCommandApi orderCommandApi;

    @Autowired
    private OrderQueryApi orderQueryApi;

    @Autowired
    private AuthApi authApi;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void paysAndRejectsRepeatedPayWithoutDoubleSelling() {
        long userId = createUser("pay-user");
        Fixture fixture = createFixture("pay", new InventoryState(3, 2, 1, 0));
        long orderId = insertOrder(userId, fixture.sessionId(), "WAIT_PAY", PAYMENT_NOW.plusMinutes(5));

        var paid = orderCommandApi.pay(userId, orderId);

        assertThat(paid.status()).isEqualTo(OrderStatus.PAID);
        assertThat(orderQueryApi.getOrder(userId, orderId).status()).isEqualTo(OrderStatus.PAID);
        assertThat(inventory(fixture.sessionId())).isEqualTo(new InventoryState(3, 2, 0, 1));
        assertInventoryInvariant(fixture.sessionId());

        assertOrderError(() -> orderCommandApi.pay(userId, orderId), OrderErrorCode.ORDER_STATE_CONFLICT);
        assertThat(inventory(fixture.sessionId())).isEqualTo(new InventoryState(3, 2, 0, 1));
    }

    @Test
    void cancelsExpiredWaitPayAndRejectsRepeatedCancelWithoutDoubleRelease() {
        long userId = createUser("cancel-user");
        Fixture fixture = createFixture("cancel", new InventoryState(3, 2, 1, 0));
        long orderId = insertOrder(userId, fixture.sessionId(), "WAIT_PAY", PAYMENT_NOW.minusMinutes(1));

        var cancelled = orderCommandApi.cancel(userId, orderId);

        assertThat(cancelled.status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(inventory(fixture.sessionId())).isEqualTo(new InventoryState(3, 3, 0, 0));
        assertInventoryInvariant(fixture.sessionId());

        assertOrderError(() -> orderCommandApi.cancel(userId, orderId), OrderErrorCode.ORDER_STATE_CONFLICT);
        assertThat(inventory(fixture.sessionId())).isEqualTo(new InventoryState(3, 3, 0, 0));
    }

    @Test
    void rejectsPayAtAndAfterExactExpiryWithoutChangingOrderOrInventory() {
        long exactUser = createUser("exact-user");
        Fixture exact = createFixture("exact-expiry", new InventoryState(1, 0, 1, 0));
        long exactOrder = insertOrder(exactUser, exact.sessionId(), "WAIT_PAY", PAYMENT_NOW);

        assertOrderError(() -> orderCommandApi.pay(exactUser, exactOrder), OrderErrorCode.ORDER_EXPIRED);
        assertThat(orderStatus(exactOrder)).isEqualTo("WAIT_PAY");
        assertThat(inventory(exact.sessionId())).isEqualTo(new InventoryState(1, 0, 1, 0));

        long pastUser = createUser("past-user");
        Fixture past = createFixture("past-expiry", new InventoryState(1, 0, 1, 0));
        long pastOrder = insertOrder(pastUser, past.sessionId(), "WAIT_PAY", PAYMENT_NOW.minusNanos(1_000));

        assertOrderError(() -> orderCommandApi.pay(pastUser, pastOrder), OrderErrorCode.ORDER_EXPIRED);
        assertThat(orderStatus(pastOrder)).isEqualTo("WAIT_PAY");
        assertThat(inventory(past.sessionId())).isEqualTo(new InventoryState(1, 0, 1, 0));
    }

    @Test
    void rollsBackPayAndCancelWhenInventoryHasNoLockedUnit() {
        long payUser = createUser("pay-rollback-user");
        Fixture payFixture = createFixture("pay-rollback", new InventoryState(1, 1, 0, 0));
        long payOrder = insertOrder(payUser, payFixture.sessionId(), "WAIT_PAY", PAYMENT_NOW.plusMinutes(5));

        assertInventoryConflict(() -> orderCommandApi.pay(payUser, payOrder));
        assertThat(orderStatus(payOrder)).isEqualTo("WAIT_PAY");
        assertThat(inventory(payFixture.sessionId())).isEqualTo(new InventoryState(1, 1, 0, 0));

        long cancelUser = createUser("cancel-rollback-user");
        Fixture cancelFixture = createFixture("cancel-rollback", new InventoryState(1, 1, 0, 0));
        long cancelOrder = insertOrder(
                cancelUser, cancelFixture.sessionId(), "WAIT_PAY", PAYMENT_NOW.plusMinutes(5));

        assertInventoryConflict(() -> orderCommandApi.cancel(cancelUser, cancelOrder));
        assertThat(orderStatus(cancelOrder)).isEqualTo("WAIT_PAY");
        assertThat(inventory(cancelFixture.sessionId())).isEqualTo(new InventoryState(1, 1, 0, 0));
    }

    @Test
    void hidesOtherOwnerAndRejectsAllTerminalStateTransitions() {
        long ownerId = createUser("owner-user");
        long otherId = createUser("other-user");
        Fixture owned = createFixture("owned", new InventoryState(1, 0, 1, 0));
        long ownedOrder = insertOrder(ownerId, owned.sessionId(), "WAIT_PAY", PAYMENT_NOW.plusMinutes(5));

        assertOrderError(() -> orderQueryApi.getOrder(otherId, ownedOrder), OrderErrorCode.ORDER_NOT_FOUND);
        assertOrderError(() -> orderCommandApi.pay(otherId, ownedOrder), OrderErrorCode.ORDER_NOT_FOUND);
        assertOrderError(() -> orderCommandApi.cancel(otherId, ownedOrder), OrderErrorCode.ORDER_NOT_FOUND);

        for (String status : List.of("PAID", "CANCELLED", "CLOSED")) {
            long userId = createUser("state-" + status.toLowerCase());
            InventoryState state = status.equals("PAID")
                    ? new InventoryState(1, 0, 0, 1)
                    : new InventoryState(1, 1, 0, 0);
            Fixture fixture = createFixture("state-" + status.toLowerCase(), state);
            long orderId = insertOrder(userId, fixture.sessionId(), status, PAYMENT_NOW.plusMinutes(5));
            assertOrderError(() -> orderCommandApi.pay(userId, orderId), OrderErrorCode.ORDER_STATE_CONFLICT);
            assertOrderError(() -> orderCommandApi.cancel(userId, orderId), OrderErrorCode.ORDER_STATE_CONFLICT);
            assertThat(inventory(fixture.sessionId())).isEqualTo(state);
        }
    }

    @Test
    void enforcesAuthenticatedOwnerOnlyHttpBoundaryAndSafeResponse() throws Exception {
        Identity owner = register("http-owner");
        Identity other = register("http-other");
        Fixture payFixture = createFixture("http-pay", new InventoryState(1, 0, 1, 0));
        long payOrder = insertOrder(
                owner.profile().getId(), payFixture.sessionId(), "WAIT_PAY", PAYMENT_NOW.plusMinutes(5));

        mockMvc.perform(get("/api/orders/{id}", payOrder)).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/orders/{id}/pay", payOrder)).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/orders/{id}/cancel", payOrder)).andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/orders/{id}", payOrder).header("Authorization", bearer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(payOrder))
                .andExpect(jsonPath("$.status").value("WAIT_PAY"))
                .andExpect(jsonPath("$.userId").doesNotExist())
                .andExpect(jsonPath("$.purchaseGuard").doesNotExist());
        mockMvc.perform(get("/api/orders/{id}", payOrder).header("Authorization", bearer(other)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
        mockMvc.perform(post("/api/orders/{id}/pay", payOrder).header("Authorization", bearer(other)))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/orders/{id}/cancel", payOrder).header("Authorization", bearer(other)))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/orders/{id}/pay", payOrder)
                        .header("Authorization", bearer(owner))
                        .header("X-User-Id", Long.toString(other.profile().getId()))
                        .queryParam("userId", Long.toString(other.profile().getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"));
        mockMvc.perform(post("/api/orders/{id}/pay", payOrder).header("Authorization", bearer(owner)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_STATE_CONFLICT"));

        Fixture cancelFixture = createFixture("http-cancel", new InventoryState(1, 0, 1, 0));
        long cancelOrder = insertOrder(
                owner.profile().getId(), cancelFixture.sessionId(), "WAIT_PAY", PAYMENT_NOW.minusMinutes(1));
        mockMvc.perform(post("/api/orders/{id}/cancel", cancelOrder).header("Authorization", bearer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        Fixture expiredFixture = createFixture("http-expired", new InventoryState(1, 0, 1, 0));
        long expiredOrder = insertOrder(
                owner.profile().getId(), expiredFixture.sessionId(), "WAIT_PAY", PAYMENT_NOW);
        mockMvc.perform(post("/api/orders/{id}/pay", expiredOrder).header("Authorization", bearer(owner)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_EXPIRED"));

        mockMvc.perform(get("/api/sessions/{id}", payFixture.sessionId())).andExpect(status().isOk());
        Fixture reservationFixture = createFixture("http-reservation", new InventoryState(1, 1, 0, 0));
        mockMvc.perform(post("/api/sessions/{id}/orders", reservationFixture.sessionId()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void payAndCancelContentionHasExactlyOneWinnerAndOneInventoryEffect() throws Exception {
        long userId = createUser("race-user");
        Fixture fixture = createFixture("race", new InventoryState(2, 1, 1, 0));
        long orderId = insertOrder(userId, fixture.sessionId(), "WAIT_PAY", PAYMENT_NOW.plusMinutes(5));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<Future<Outcome>> futures = new ArrayList<>();
        try {
            futures.add(executor.submit(() -> runContender(ready, start, () -> orderCommandApi.pay(userId, orderId))));
            futures.add(executor.submit(
                    () -> runContender(ready, start, () -> orderCommandApi.cancel(userId, orderId))));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            executor.shutdown();
            assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

            List<Outcome> outcomes = new ArrayList<>();
            for (Future<Outcome> future : futures) {
                outcomes.add(future.get(1, TimeUnit.SECONDS));
            }
            assertThat(outcomes).filteredOn(outcome -> outcome == Outcome.SUCCESS).hasSize(1);
            assertThat(outcomes).filteredOn(outcome -> outcome == Outcome.STATE_CONFLICT).hasSize(1);

            String finalStatus = orderStatus(orderId);
            InventoryState finalInventory = inventory(fixture.sessionId());
            assertThat(finalStatus).isIn("PAID", "CANCELLED");
            if (finalStatus.equals("PAID")) {
                assertThat(finalInventory).isEqualTo(new InventoryState(2, 1, 0, 1));
            } else {
                assertThat(finalInventory).isEqualTo(new InventoryState(2, 2, 0, 0));
            }
            assertInventoryInvariant(fixture.sessionId());
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @AfterEach
    void cleanupAndVerifyNoSyntheticResidue() {
        jdbcTemplate.update("DELETE o FROM ticket_order o JOIN event_sessions s ON s.id = o.session_id "
                + "WHERE s.name LIKE ?", TEST_PREFIX + "%");
        jdbcTemplate.update("DELETE i FROM event_inventory i JOIN event_sessions s ON s.id = i.session_id "
                + "WHERE s.name LIKE ?", TEST_PREFIX + "%");
        jdbcTemplate.update("DELETE FROM event_sessions WHERE name LIKE ?", TEST_PREFIX + "%");
        jdbcTemplate.update("DELETE FROM events WHERE title LIKE ?", TEST_PREFIX + "%");
        jdbcTemplate.update("DELETE FROM users WHERE username LIKE ?", TEST_PREFIX + "%");

        assertThat(count("SELECT COUNT(*) FROM ticket_order o JOIN event_sessions s ON s.id = o.session_id "
                + "WHERE s.name LIKE ?", TEST_PREFIX + "%")).isZero();
        assertThat(count("SELECT COUNT(*) FROM event_inventory i JOIN event_sessions s ON s.id = i.session_id "
                + "WHERE s.name LIKE ?", TEST_PREFIX + "%")).isZero();
        assertThat(count("SELECT COUNT(*) FROM event_sessions WHERE name LIKE ?", TEST_PREFIX + "%")).isZero();
        assertThat(count("SELECT COUNT(*) FROM events WHERE title LIKE ?", TEST_PREFIX + "%")).isZero();
        assertThat(count("SELECT COUNT(*) FROM users WHERE username LIKE ?", TEST_PREFIX + "%")).isZero();
    }

    private Outcome runContender(CountDownLatch ready, CountDownLatch start, OrderCall call) throws Exception {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Contention start latch timed out");
        }
        try {
            call.execute();
            return Outcome.SUCCESS;
        } catch (OrderException exception) {
            if (exception.getErrorCode() == OrderErrorCode.ORDER_STATE_CONFLICT) {
                return Outcome.STATE_CONFLICT;
            }
            throw exception;
        }
    }

    private Identity register(String scenario) {
        UserProfile profile = authApi.register(new RegisterCommand(
                TEST_PREFIX + scenario + '-' + shortId(), PASSWORD, scenario));
        AccessToken accessToken = authApi.login(new LoginCommand(profile.getUsername(), PASSWORD));
        return new Identity(profile, accessToken);
    }

    private long createUser(String scenario) {
        String username = TEST_PREFIX + scenario + '-' + shortId();
        jdbcTemplate.update("INSERT INTO users (username, password_hash, display_name, role) "
                        + "VALUES (?, ?, ?, 'USER')",
                username, ENCODED_PASSWORD, scenario);
        return jdbcTemplate.queryForObject("SELECT id FROM users WHERE username = ?", Long.class, username);
    }

    private Fixture createFixture(String scenario, InventoryState inventory) {
        String suffix = scenario + '-' + shortId();
        String title = TEST_PREFIX + suffix;
        jdbcTemplate.update("INSERT INTO events (organizer_user_id, title, status) VALUES (?, ?, 'PUBLISHED')",
                9_604L, title);
        long eventId = jdbcTemplate.queryForObject("SELECT id FROM events WHERE title = ?", Long.class, title);

        String sessionName = TEST_PREFIX + suffix;
        jdbcTemplate.update("INSERT INTO event_sessions "
                        + "(event_id, name, sale_start_at, sale_end_at, start_at, end_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                eventId, sessionName, PAYMENT_NOW.minusHours(1), PAYMENT_NOW.plusHours(1),
                PAYMENT_NOW.plusHours(2), PAYMENT_NOW.plusHours(3));
        long sessionId = jdbcTemplate.queryForObject(
                "SELECT id FROM event_sessions WHERE name = ?", Long.class, sessionName);
        jdbcTemplate.update("INSERT INTO event_inventory "
                        + "(session_id, total_stock, available_stock, locked_stock, sold_stock) "
                        + "VALUES (?, ?, ?, ?, ?)",
                sessionId, inventory.total(), inventory.available(), inventory.locked(), inventory.sold());
        return new Fixture(eventId, sessionId);
    }

    private long insertOrder(long userId, long sessionId, String status, LocalDateTime expireAt) {
        String orderNo = "TEST-" + UUID.randomUUID().toString().replace("-", "").toUpperCase();
        jdbcTemplate.update("INSERT INTO ticket_order "
                        + "(order_no, user_id, session_id, status, expire_at) VALUES (?, ?, ?, ?, ?)",
                orderNo, userId, sessionId, status, expireAt);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM ticket_order WHERE order_no = ?", Long.class, orderNo);
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

    private String orderStatus(long orderId) {
        return jdbcTemplate.queryForObject("SELECT status FROM ticket_order WHERE id = ?", String.class, orderId);
    }

    private long count(String sql, Object value) {
        Long count = jdbcTemplate.queryForObject(sql, Long.class, value);
        return count == null ? 0 : count;
    }

    private static void assertOrderError(OrderCall call, OrderErrorCode expected) {
        assertThatThrownBy(call::execute)
                .isInstanceOfSatisfying(OrderException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(expected));
    }

    private static void assertInventoryConflict(OrderCall call) {
        assertThatThrownBy(call::execute)
                .isInstanceOfSatisfying(InventoryException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(InventoryErrorCode.INVENTORY_STATE_CONFLICT));
    }

    private static String bearer(Identity identity) {
        return "Bearer " + identity.accessToken().getTokenValue();
    }

    private static String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    @FunctionalInterface
    private interface OrderCall {
        void execute() throws Exception;
    }

    private enum Outcome {
        SUCCESS,
        STATE_CONFLICT
    }

    private record Fixture(long eventId, long sessionId) {
    }

    private record Identity(UserProfile profile, AccessToken accessToken) {
    }

    private record InventoryState(int total, int available, int locked, int sold) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfiguration {

        @Bean
        @Primary
        Clock orderLifecycleTestClock() {
            return Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
        }
    }
}

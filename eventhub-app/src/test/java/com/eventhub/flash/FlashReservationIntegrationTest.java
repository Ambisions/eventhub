package com.eventhub.flash;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.eventhub.auth.api.model.AccessTokenContract;
import com.eventhub.inventory.api.FlashAdmissionApi;
import com.eventhub.inventory.api.command.ReserveFlashAdmissionCommand;
import com.eventhub.inventory.api.model.FlashAdmissionResult;
import com.eventhub.notification.api.NotificationEventPublisherApi;
import com.eventhub.order.api.OrderTimeoutApi;
import com.eventhub.application.flash.FlashOrderLifecycleApplicationService;
import com.eventhub.scheduler.OrderTimeoutScheduler;
import com.eventhub.user.api.model.UserRole;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FlashReservationIntegrationTest {

    private static final String PREFIX = "phase10-flash-reserve-";
    private static final String ENCODED_PASSWORD = "{bcrypt}$2a$10$phase10.synthetic.credential";
    private static final int CONCURRENCY_TIMEOUT_SECONDS = 60;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private FlashAdmissionApi flashAdmissionApi;

    @Autowired
    private OrderTimeoutApi orderTimeoutApi;

    @Autowired
    private FlashOrderLifecycleApplicationService flashOrderLifecycleApplicationService;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private NotificationEventPublisherApi notificationEventPublisherApi;

    private final List<Long> sessionIds = new ArrayList<>();
    private final List<Long> eventIds = new ArrayList<>();
    private final List<Long> userIds = new ArrayList<>();

    @AfterEach
    void cleanupAndVerifyNoSyntheticResidue() {
        for (long sessionId : sessionIds) {
            redisTemplate.delete(List.of(metaKey(sessionId), admissionsKey(sessionId)));
        }
        for (long sessionId : sessionIds) {
            jdbcTemplate.update("DELETE FROM ticket WHERE session_id = ?", sessionId);
            jdbcTemplate.update("DELETE FROM ticket_order WHERE session_id = ?", sessionId);
            jdbcTemplate.update("DELETE FROM event_inventory WHERE session_id = ?", sessionId);
            jdbcTemplate.update("DELETE FROM event_sessions WHERE id = ?", sessionId);
        }
        for (long eventId : eventIds) {
            jdbcTemplate.update("DELETE FROM events WHERE id = ?", eventId);
        }
        for (long userId : userIds) {
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
        }

        assertThat(count("SELECT COUNT(*) FROM event_sessions WHERE name LIKE ?", PREFIX + "%")).isZero();
        assertThat(count("SELECT COUNT(*) FROM users WHERE username LIKE ?", PREFIX + "%")).isZero();
    }

    @Test
    void authenticatesUsersAndAdminsAndCompletesRealRedisToMySqlToActivateFlow() throws Exception {
        Fixture fixture = fixture("happy", 2, Window.OPEN);
        Identity user = identity("happy-user", UserRole.USER);
        Identity admin = identity("happy-admin", UserRole.ADMIN);
        prepareRedis(fixture, 2, Window.OPEN);

        mockMvc.perform(post(path(fixture))).andExpect(status().isUnauthorized());
        assertThat(orderCount(fixture.sessionId())).isZero();

        JsonNode first = responseJson(mockMvc.perform(post(path(fixture))
                        .header(HttpHeaders.AUTHORIZATION, user.token()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("WAIT_PAY"))
                .andReturn());
        long firstOrderId = first.get("id").asLong();
        assertStableSuccess(fixture, user.userId(), firstOrderId, 1, 1);

        mockMvc.perform(post(path(fixture))
                        .header(HttpHeaders.AUTHORIZATION, user.token()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FLASH_ALREADY_ADMITTED"));
        assertThat(orderCount(fixture.sessionId())).isEqualTo(1);

        JsonNode second = responseJson(mockMvc.perform(post(path(fixture))
                        .header(HttpHeaders.AUTHORIZATION, admin.token()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("WAIT_PAY"))
                .andReturn());
        long secondOrderId = second.get("id").asLong();
        assertStableSuccess(fixture, admin.userId(), secondOrderId, 0, 2);
    }

    @Test
    void mapsSoldOutWindowsNotPreparedAndPendingWithoutCreatingOrders() throws Exception {
        Identity user = identity("mapping", UserRole.USER);

        Fixture soldOut = fixture("sold-out", 0, Window.OPEN);
        prepareRedis(soldOut, 0, Window.OPEN);
        assertFlashFailure(soldOut, user, 409, "SOLD_OUT");

        Fixture before = fixture("before", 1, Window.BEFORE);
        prepareRedis(before, 1, Window.BEFORE);
        assertFlashFailure(before, user, 409, "SALE_NOT_STARTED");

        Fixture ended = fixture("ended", 1, Window.ENDED);
        prepareRedis(ended, 1, Window.ENDED);
        assertFlashFailure(ended, user, 409, "SALE_ENDED");

        Fixture notPrepared = fixture("not-prepared", 1, Window.OPEN);
        assertFlashFailure(notPrepared, user, 503, "FLASH_SERVICE_UNAVAILABLE");

        Fixture pending = fixture("pending", 1, Window.OPEN);
        prepareRedis(pending, 1, Window.OPEN);
        assertThat(flashAdmissionApi.reserve(new ReserveFlashAdmissionCommand(
                pending.sessionId(), user.userId(), "fixture-admission")))
                .isEqualTo(FlashAdmissionResult.ADMITTED);
        assertFlashFailure(pending, user, 503, "FLASH_SERVICE_UNAVAILABLE");
        assertThat(redisTemplate.opsForHash().get(admissionsKey(pending.sessionId()),
                Long.toString(user.userId()))).isEqualTo("P:fixture-admission");
    }

    @Test
    void activePurchaseDbGuardRejectsAndDeterministicallyRestoresRedisAdmission() throws Exception {
        Fixture fixture = fixture("db-guard", 2, Window.OPEN);
        Identity user = identity("db-guard", UserRole.USER);
        prepareRedis(fixture, 2, Window.OPEN);
        jdbcTemplate.update("""
                INSERT INTO ticket_order (order_no, user_id, session_id, status, expire_at)
                VALUES (?, ?, ?, 'WAIT_PAY', ?)
                """, "EH-GUARD-" + shortId(), user.userId(), fixture.sessionId(),
                LocalDateTime.now(ZoneOffset.UTC).plusMinutes(15));

        mockMvc.perform(post(path(fixture))
                        .header(HttpHeaders.AUTHORIZATION, user.token()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACTIVE_PURCHASE_EXISTS"));

        assertThat(orderCount(fixture.sessionId())).isEqualTo(1);
        assertThat(redisTemplate.opsForHash().get(metaKey(fixture.sessionId()), "stock")).isEqualTo("2");
        assertThat(redisTemplate.opsForHash().hasKey(
                admissionsKey(fixture.sessionId()), Long.toString(user.userId()))).isFalse();
        assertThat(inventory(fixture.sessionId())).isEqualTo(new InventoryState(2, 2, 0, 0));
    }

    @Test
    void capacityOneAllowsExactlyOneWaitPayOrderWithoutOversell() throws Exception {
        Fixture fixture = fixture("capacity-one", 1, Window.OPEN);
        prepareRedis(fixture, 1, Window.OPEN);
        List<Identity> identities = new ArrayList<>();
        for (int index = 0; index < 20; index++) {
            identities.add(identity("capacity-" + index, UserRole.USER));
        }

        List<Integer> statuses = runConcurrently(identities.stream()
                .map(identity -> (HttpCall) () -> mockMvc.perform(post(path(fixture))
                                .header(HttpHeaders.AUTHORIZATION, identity.token()))
                        .andReturn().getResponse().getStatus())
                .toList());

        assertThat(statuses).filteredOn(status -> status == 201).hasSize(1);
        assertThat(statuses).filteredOn(status -> status == 409).hasSize(19);
        assertThat(orderCount(fixture.sessionId())).isEqualTo(1);
        assertThat(inventory(fixture.sessionId())).isEqualTo(new InventoryState(1, 0, 1, 0));
        assertThat(redisTemplate.opsForHash().get(metaKey(fixture.sessionId()), "stock")).isEqualTo("0");
        assertThat(redisTemplate.opsForHash().size(admissionsKey(fixture.sessionId()))).isEqualTo(1L);
        assertInventoryInvariant(fixture.sessionId());
    }

    @Test
    void sameUserConcurrencyCreatesOneOrderAndOneAdmission() throws Exception {
        Fixture fixture = fixture("same-user", 10, Window.OPEN);
        Identity user = identity("same-user", UserRole.USER);
        prepareRedis(fixture, 10, Window.OPEN);
        List<HttpCall> calls = new ArrayList<>();
        for (int index = 0; index < 10; index++) {
            calls.add(() -> mockMvc.perform(post(path(fixture))
                            .header(HttpHeaders.AUTHORIZATION, user.token()))
                    .andReturn().getResponse().getStatus());
        }

        List<Integer> statuses = runConcurrently(calls);

        assertThat(statuses).filteredOn(status -> status == 201).hasSize(1);
        assertThat(statuses).allSatisfy(status -> assertThat(status).isIn(201, 409, 503));
        assertThat(orderCount(fixture.sessionId())).isEqualTo(1);
        assertThat(inventory(fixture.sessionId())).isEqualTo(new InventoryState(10, 9, 1, 0));
        assertThat(redisTemplate.opsForHash().get(metaKey(fixture.sessionId()), "stock")).isEqualTo("9");
        assertThat(redisTemplate.opsForHash().size(admissionsKey(fixture.sessionId()))).isEqualTo(1L);
        assertInventoryInvariant(fixture.sessionId());
    }

    @Test
    void cancelAndTimeoutSynchronizeFlashRedisOnlyAfterDatabaseSuccess() throws Exception {
        Fixture cancelFixture = fixture("lifecycle-cancel", 2, Window.OPEN);
        Identity cancelUser = identity("lifecycle-cancel", UserRole.USER);
        prepareRedis(cancelFixture, 2, Window.OPEN);
        long cancelOrderId = reserve(cancelFixture, cancelUser);

        mockMvc.perform(post("/api/orders/{orderId}/cancel", cancelOrderId)
                        .header(HttpHeaders.AUTHORIZATION, cancelUser.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        assertThat(orderStatus(cancelOrderId)).isEqualTo("CANCELLED");
        assertThat(inventory(cancelFixture.sessionId())).isEqualTo(new InventoryState(2, 2, 0, 0));
        assertThat(redisTemplate.opsForHash().get(metaKey(cancelFixture.sessionId()), "stock"))
                .isEqualTo("2");
        assertThat(redisTemplate.opsForHash().hasKey(
                admissionsKey(cancelFixture.sessionId()), Long.toString(cancelUser.userId()))).isFalse();

        Fixture timeoutFixture = fixture("lifecycle-timeout", 2, Window.OPEN);
        Identity timeoutUser = identity("lifecycle-timeout", UserRole.USER);
        prepareRedis(timeoutFixture, 2, Window.OPEN);
        long timeoutOrderId = reserve(timeoutFixture, timeoutUser);
        jdbcTemplate.update("UPDATE ticket_order SET expire_at = ? WHERE id = ?",
                LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1), timeoutOrderId);

        new OrderTimeoutScheduler(orderTimeoutApi, flashOrderLifecycleApplicationService, 100)
                .processExpiredOrders();

        assertThat(orderStatus(timeoutOrderId)).isEqualTo("CLOSED");
        assertThat(inventory(timeoutFixture.sessionId())).isEqualTo(new InventoryState(2, 2, 0, 0));
        assertThat(redisTemplate.opsForHash().get(metaKey(timeoutFixture.sessionId()), "stock"))
                .isEqualTo("2");
        assertThat(redisTemplate.opsForHash().hasKey(
                admissionsKey(timeoutFixture.sessionId()), Long.toString(timeoutUser.userId()))).isFalse();
    }

    @Test
    void refundTransitionsAdmissionToHistoricalFenceAndBlocksFutureAdmission() throws Exception {
        Fixture fixture = fixture("lifecycle-refund", 2, Window.OPEN);
        Identity user = identity("lifecycle-refund", UserRole.USER);
        prepareRedis(fixture, 2, Window.OPEN);
        long orderId = reserve(fixture, user);

        mockMvc.perform(post("/api/orders/{orderId}/pay", orderId)
                        .header(HttpHeaders.AUTHORIZATION, user.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.order.status").value("PAID"));
        assertThat(redisTemplate.opsForHash().get(
                admissionsKey(fixture.sessionId()), Long.toString(user.userId())))
                .isEqualTo("A:" + orderId);

        mockMvc.perform(post("/api/orders/{orderId}/refund", orderId)
                        .header(HttpHeaders.AUTHORIZATION, user.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REFUNDED"));

        assertThat(orderStatus(orderId)).isEqualTo("REFUNDED");
        assertThat(ticketStatus(orderId)).isEqualTo("REFUNDED");
        assertThat(inventory(fixture.sessionId())).isEqualTo(new InventoryState(2, 2, 0, 0));
        assertThat(redisTemplate.opsForHash().get(metaKey(fixture.sessionId()), "stock")).isEqualTo("2");
        assertThat(redisTemplate.opsForHash().get(
                admissionsKey(fixture.sessionId()), Long.toString(user.userId())))
                .isEqualTo("R:" + orderId);

        mockMvc.perform(post(path(fixture)).header(HttpHeaders.AUTHORIZATION, user.token()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FLASH_ALREADY_ADMITTED"));
        assertInventoryInvariant(fixture.sessionId());
    }

    @Test
    void repeatedRefundRepairsFirstPostCommitRedisFailureWithoutRepeatingDatabaseEffects() throws Exception {
        Fixture fixture = fixture("refund-repair", 2, Window.OPEN);
        Identity user = identity("refund-repair", UserRole.USER);
        prepareRedis(fixture, 2, Window.OPEN);
        long orderId = reserve(fixture, user);
        mockMvc.perform(post("/api/orders/{orderId}/pay", orderId)
                        .header(HttpHeaders.AUTHORIZATION, user.token()))
                .andExpect(status().isOk());

        redisTemplate.opsForHash().put(metaKey(fixture.sessionId()), "state", "BROKEN");
        mockMvc.perform(post("/api/orders/{orderId}/refund", orderId)
                        .header(HttpHeaders.AUTHORIZATION, user.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REFUNDED"));

        assertThat(orderStatus(orderId)).isEqualTo("REFUNDED");
        assertThat(ticketStatus(orderId)).isEqualTo("REFUNDED");
        assertThat(inventory(fixture.sessionId())).isEqualTo(new InventoryState(2, 2, 0, 0));
        assertThat(redisTemplate.opsForHash().get(metaKey(fixture.sessionId()), "stock")).isEqualTo("1");
        assertThat(redisTemplate.opsForHash().get(
                admissionsKey(fixture.sessionId()), Long.toString(user.userId())))
                .isEqualTo("A:" + orderId);

        redisTemplate.opsForHash().put(metaKey(fixture.sessionId()), "state", "PREPARED");
        mockMvc.perform(post("/api/orders/{orderId}/refund", orderId)
                        .header(HttpHeaders.AUTHORIZATION, user.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REFUNDED"));

        assertThat(inventory(fixture.sessionId())).isEqualTo(new InventoryState(2, 2, 0, 0));
        assertThat(redisTemplate.opsForHash().get(metaKey(fixture.sessionId()), "stock")).isEqualTo("2");
        assertThat(redisTemplate.opsForHash().get(
                admissionsKey(fixture.sessionId()), Long.toString(user.userId())))
                .isEqualTo("R:" + orderId);
        assertThat(count("SELECT COUNT(*) FROM ticket WHERE order_id = ?", orderId)).isEqualTo(1);
    }

    @Test
    void pendingAdmissionIsNeverMutatedByCancelTimeoutOrRefundLifecycleSync() throws Exception {
        Fixture cancelFixture = fixture("pending-cancel", 2, Window.OPEN);
        Identity cancelUser = identity("pending-cancel", UserRole.USER);
        prepareRedis(cancelFixture, 2, Window.OPEN);
        long cancelOrder = reserve(cancelFixture, cancelUser);
        forcePending(cancelFixture, cancelUser, "cancel-pending");
        mockMvc.perform(post("/api/orders/{orderId}/cancel", cancelOrder)
                        .header(HttpHeaders.AUTHORIZATION, cancelUser.token()))
                .andExpect(status().isOk());
        assertPendingUnchanged(cancelFixture, cancelUser, "cancel-pending", "1");
        assertThat(orderStatus(cancelOrder)).isEqualTo("CANCELLED");

        Fixture timeoutFixture = fixture("pending-timeout", 2, Window.OPEN);
        Identity timeoutUser = identity("pending-timeout", UserRole.USER);
        prepareRedis(timeoutFixture, 2, Window.OPEN);
        long timeoutOrder = reserve(timeoutFixture, timeoutUser);
        forcePending(timeoutFixture, timeoutUser, "timeout-pending");
        jdbcTemplate.update("UPDATE ticket_order SET expire_at = ? WHERE id = ?",
                LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1), timeoutOrder);
        new OrderTimeoutScheduler(orderTimeoutApi, flashOrderLifecycleApplicationService, 100)
                .processExpiredOrders();
        assertPendingUnchanged(timeoutFixture, timeoutUser, "timeout-pending", "1");
        assertThat(orderStatus(timeoutOrder)).isEqualTo("CLOSED");

        Fixture refundFixture = fixture("pending-refund", 2, Window.OPEN);
        Identity refundUser = identity("pending-refund", UserRole.USER);
        prepareRedis(refundFixture, 2, Window.OPEN);
        long refundOrder = reserve(refundFixture, refundUser);
        mockMvc.perform(post("/api/orders/{orderId}/pay", refundOrder)
                        .header(HttpHeaders.AUTHORIZATION, refundUser.token()))
                .andExpect(status().isOk());
        forcePending(refundFixture, refundUser, "refund-pending");
        mockMvc.perform(post("/api/orders/{orderId}/refund", refundOrder)
                        .header(HttpHeaders.AUTHORIZATION, refundUser.token()))
                .andExpect(status().isOk());
        assertPendingUnchanged(refundFixture, refundUser, "refund-pending", "1");
        assertThat(orderStatus(refundOrder)).isEqualTo("REFUNDED");
    }

    @Test
    void eventLookupFailureAfterCancelCommitDoesNotReplaceHttpSuccess() throws Exception {
        Fixture fixture = fixture("event-query-failure", 1, Window.OPEN);
        Identity user = identity("event-query-failure", UserRole.USER);
        prepareRedis(fixture, 1, Window.OPEN);
        long orderId = reserve(fixture, user);
        jdbcTemplate.update("UPDATE events SET status = 'DRAFT' WHERE id = ?", fixture.eventId());

        mockMvc.perform(post("/api/orders/{orderId}/cancel", orderId)
                        .header(HttpHeaders.AUTHORIZATION, user.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        assertThat(orderStatus(orderId)).isEqualTo("CANCELLED");
        assertThat(inventory(fixture.sessionId())).isEqualTo(new InventoryState(1, 1, 0, 0));
        assertThat(redisTemplate.opsForHash().get(metaKey(fixture.sessionId()), "stock")).isEqualTo("0");
        assertThat(redisTemplate.opsForHash().get(
                admissionsKey(fixture.sessionId()), Long.toString(user.userId())))
                .isEqualTo("A:" + orderId);
    }

    @Test
    void timeoutLifecycleFailureForOneClosedOrderDoesNotBlockLaterRedisSync() throws Exception {
        Fixture first = fixture("timeout-isolation-first", 1, Window.OPEN);
        Identity firstUser = identity("timeout-isolation-first", UserRole.USER);
        prepareRedis(first, 1, Window.OPEN);
        long firstOrder = reserve(first, firstUser);
        redisTemplate.opsForHash().put(metaKey(first.sessionId()), "state", "BROKEN");

        Fixture second = fixture("timeout-isolation-second", 1, Window.OPEN);
        Identity secondUser = identity("timeout-isolation-second", UserRole.USER);
        prepareRedis(second, 1, Window.OPEN);
        long secondOrder = reserve(second, secondUser);
        LocalDateTime expired = LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1);
        jdbcTemplate.update("UPDATE ticket_order SET expire_at = ? WHERE id IN (?, ?)",
                expired, firstOrder, secondOrder);

        new OrderTimeoutScheduler(orderTimeoutApi, flashOrderLifecycleApplicationService, 100)
                .processExpiredOrders();

        assertThat(orderStatus(firstOrder)).isEqualTo("CLOSED");
        assertThat(orderStatus(secondOrder)).isEqualTo("CLOSED");
        assertThat(inventory(first.sessionId())).isEqualTo(new InventoryState(1, 1, 0, 0));
        assertThat(inventory(second.sessionId())).isEqualTo(new InventoryState(1, 1, 0, 0));
        assertThat(redisTemplate.opsForHash().get(metaKey(first.sessionId()), "stock")).isEqualTo("0");
        assertThat(redisTemplate.opsForHash().get(
                admissionsKey(first.sessionId()), Long.toString(firstUser.userId())))
                .isEqualTo("A:" + firstOrder);
        assertThat(redisTemplate.opsForHash().get(metaKey(second.sessionId()), "stock")).isEqualTo("1");
        assertThat(redisTemplate.opsForHash().hasKey(
                admissionsKey(second.sessionId()), Long.toString(secondUser.userId()))).isFalse();
    }

    private void assertFlashFailure(
            Fixture fixture, Identity identity, int expectedStatus, String expectedCode) throws Exception {
        mockMvc.perform(post(path(fixture))
                        .header(HttpHeaders.AUTHORIZATION, identity.token()))
                .andExpect(status().is(expectedStatus))
                .andExpect(jsonPath("$.code").value(expectedCode));
        assertThat(orderCount(fixture.sessionId())).isZero();
    }

    private long reserve(Fixture fixture, Identity identity) throws Exception {
        return responseJson(mockMvc.perform(post(path(fixture))
                        .header(HttpHeaders.AUTHORIZATION, identity.token()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("WAIT_PAY"))
                .andReturn()).get("id").asLong();
    }

    private void forcePending(Fixture fixture, Identity identity, String admissionId) {
        redisTemplate.opsForHash().put(
                admissionsKey(fixture.sessionId()),
                Long.toString(identity.userId()),
                "P:" + admissionId);
    }

    private void assertPendingUnchanged(
            Fixture fixture, Identity identity, String admissionId, String expectedStock) {
        assertThat(redisTemplate.opsForHash().get(metaKey(fixture.sessionId()), "stock"))
                .isEqualTo(expectedStock);
        assertThat(redisTemplate.opsForHash().get(
                admissionsKey(fixture.sessionId()), Long.toString(identity.userId())))
                .isEqualTo("P:" + admissionId);
    }

    private void assertStableSuccess(
            Fixture fixture, long userId, long orderId, int redisStock, int locked) {
        assertThat(count("SELECT COUNT(*) FROM ticket_order WHERE id = ? AND user_id = ? "
                + "AND session_id = ? AND status = 'WAIT_PAY'", orderId, userId, fixture.sessionId()))
                .isEqualTo(1);
        assertThat(redisTemplate.opsForHash().get(metaKey(fixture.sessionId()), "stock"))
                .isEqualTo(Integer.toString(redisStock));
        assertThat(redisTemplate.opsForHash().get(
                admissionsKey(fixture.sessionId()), Long.toString(userId)))
                .isEqualTo("A:" + orderId);
        assertThat(inventory(fixture.sessionId())).isEqualTo(
                new InventoryState(2, 2 - locked, locked, 0));
        assertInventoryInvariant(fixture.sessionId());
    }

    private Fixture fixture(String scenario, int stock, Window window) {
        String suffix = scenario + '-' + shortId();
        String title = PREFIX + suffix;
        jdbcTemplate.update("INSERT INTO events (organizer_user_id, title, status) VALUES (?, ?, 'PUBLISHED')",
                91_000L, title);
        long eventId = requiredLong("SELECT id FROM events WHERE title = ?", title);
        eventIds.add(eventId);

        WindowTimes times = times(window);
        String sessionName = PREFIX + "session-" + suffix;
        jdbcTemplate.update("""
                INSERT INTO event_sessions (
                    event_id, name, sale_mode, sale_start_at, sale_end_at, start_at, end_at)
                VALUES (?, ?, 'FLASH', ?, ?, ?, ?)
                """, eventId, sessionName, times.start(), times.end(),
                times.end().plusHours(1), times.end().plusHours(2));
        long sessionId = requiredLong("SELECT id FROM event_sessions WHERE name = ?", sessionName);
        sessionIds.add(sessionId);
        jdbcTemplate.update("""
                INSERT INTO event_inventory (
                    session_id, total_stock, available_stock, locked_stock, sold_stock)
                VALUES (?, ?, ?, 0, 0)
                """, sessionId, stock, stock);
        return new Fixture(eventId, sessionId, stock, times);
    }

    private Identity identity(String scenario, UserRole role) {
        String username = PREFIX + scenario + '-' + shortId();
        jdbcTemplate.update("INSERT INTO users (username, password_hash, display_name, role) "
                        + "VALUES (?, ?, ?, ?)",
                username, ENCODED_PASSWORD, scenario, role.name());
        long userId = requiredLong("SELECT id FROM users WHERE username = ?", username);
        userIds.add(userId);
        return new Identity(userId, bearerToken(userId, username, role));
    }

    private void prepareRedis(Fixture fixture, int stock, Window window) {
        WindowTimes times = fixture.times();
        redisTemplate.opsForHash().putAll(metaKey(fixture.sessionId()), Map.of(
                "state", "PREPARED",
                "capacity", Integer.toString(fixture.capacity()),
                "stock", Integer.toString(stock),
                "saleStartUs", Long.toString(epochMicros(times.start())),
                "saleEndUs", Long.toString(epochMicros(times.end()))));
        redisTemplate.delete(admissionsKey(fixture.sessionId()));
    }

    private List<Integer> runConcurrently(List<HttpCall> calls) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(calls.size());
        CountDownLatch ready = new CountDownLatch(calls.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        try {
            for (HttpCall call : calls) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(15, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("start latch timed out");
                    }
                    return call.execute();
                }));
            }
            assertThat(ready.await(20, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            executor.shutdown();
            assertThat(executor.awaitTermination(CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> future : futures) {
                statuses.add(future.get(2, TimeUnit.SECONDS));
            }
            return statuses;
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private String bearerToken(long userId, String username, UserRole role) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(AccessTokenContract.ISSUER)
                .subject(Long.toString(userId))
                .issuedAt(now.minusSeconds(1))
                .expiresAt(now.plus(AccessTokenContract.TIME_TO_LIVE))
                .claim(AccessTokenContract.USERNAME_CLAIM, username)
                .claim(AccessTokenContract.ROLE_CLAIM, role.name())
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).type("JWT").build();
        return "Bearer " + jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    private JsonNode responseJson(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
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

    private String orderStatus(long orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM ticket_order WHERE id = ?", String.class, orderId);
    }

    private String ticketStatus(long orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM ticket WHERE order_id = ?", String.class, orderId);
    }

    private void assertInventoryInvariant(long sessionId) {
        InventoryState state = inventory(sessionId);
        assertThat(state.total()).isEqualTo(state.available() + state.locked() + state.sold());
        assertThat(state.available()).isNotNegative();
        assertThat(state.locked()).isNotNegative();
        assertThat(state.sold()).isNotNegative();
    }

    private long orderCount(long sessionId) {
        return count("SELECT COUNT(*) FROM ticket_order WHERE session_id = ?", sessionId);
    }

    private long count(String sql, Object... arguments) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, arguments);
        return value == null ? 0L : value;
    }

    private long requiredLong(String sql, Object... arguments) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, arguments);
        if (value == null) {
            throw new IllegalStateException("fixture id was not generated");
        }
        return value;
    }

    private static WindowTimes times(Window window) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        return switch (window) {
            case OPEN -> new WindowTimes(now.minusMinutes(2), now.plusMinutes(10));
            case BEFORE -> new WindowTimes(now.plusMinutes(10), now.plusMinutes(20));
            case ENDED -> new WindowTimes(now.minusMinutes(20), now.minusMinutes(10));
        };
    }

    private static long epochMicros(LocalDateTime value) {
        Instant instant = value.toInstant(ZoneOffset.UTC);
        return Math.addExact(Math.multiplyExact(instant.getEpochSecond(), 1_000_000L),
                instant.getNano() / 1_000L);
    }

    private static String path(Fixture fixture) {
        return "/api/sessions/" + fixture.sessionId() + "/flash/reserve";
    }

    private static String metaKey(long sessionId) {
        return "eventhub:flash:{" + sessionId + "}:meta";
    }

    private static String admissionsKey(long sessionId) {
        return "eventhub:flash:{" + sessionId + "}:admissions";
    }

    private static String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    @FunctionalInterface
    private interface HttpCall {
        int execute() throws Exception;
    }

    private enum Window {
        OPEN,
        BEFORE,
        ENDED
    }

    private record WindowTimes(LocalDateTime start, LocalDateTime end) {
    }

    private record Fixture(long eventId, long sessionId, int capacity, WindowTimes times) {
    }

    private record Identity(long userId, String token) {
    }

    private record InventoryState(int total, int available, int locked, int sold) {
    }
}

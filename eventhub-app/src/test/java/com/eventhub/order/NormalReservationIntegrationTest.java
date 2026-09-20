package com.eventhub.order;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import com.eventhub.auth.api.AuthApi;
import com.eventhub.auth.api.command.LoginCommand;
import com.eventhub.auth.api.command.RegisterCommand;
import com.eventhub.auth.api.model.AccessToken;
import com.eventhub.event.api.error.EventErrorCode;
import com.eventhub.event.api.error.EventException;
import com.eventhub.inventory.api.error.InventoryErrorCode;
import com.eventhub.inventory.api.error.InventoryException;
import com.eventhub.order.api.ReservationApi;
import com.eventhub.order.api.error.OrderErrorCode;
import com.eventhub.order.api.error.OrderException;
import com.eventhub.order.api.model.OrderStatus;
import com.eventhub.user.api.model.UserProfile;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
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
class NormalReservationIntegrationTest {

    private static final String TEST_PREFIX = "phase6-order-basic-";
    private static final String PASSWORD = "valid-password";
    private static final String ENCODED_PASSWORD = "{bcrypt}$2a$10$phase6.synthetic.credential";

    @Autowired
    private ReservationApi reservationApi;

    @Autowired
    private AuthApi authApi;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void persistsWaitPayAndLocksExactlyOneInventoryUnitAtomically() {
        long userId = createUser("success-user");
        Fixture fixture = createFixture("success", "PUBLISHED", Window.OPEN, 4);
        LocalDateTime before = utcNow();

        var result = reservationApi.reserve(userId, fixture.sessionId());

        LocalDateTime after = utcNow();
        assertThat(result.status()).isEqualTo(OrderStatus.WAIT_PAY);
        assertThat(result.orderNo()).matches("EH[0-9A-F]{32}");
        assertThat(result.sessionId()).isEqualTo(fixture.sessionId());
        assertThat(result.createdAt()).isNotNull();
        assertThat(result.updatedAt()).isNotNull();
        assertThat(result.expireAt()).isBetween(before.plusMinutes(15), after.plusMinutes(15));
        assertThat(Duration.between(result.createdAt(), result.expireAt()).toMillis())
                .isBetween(14 * 60_000L, 16 * 60_000L);

        assertThat(orderStatus(result.id())).isEqualTo("WAIT_PAY");
        assertThat(orderUserId(result.id())).isEqualTo(userId);
        assertThat(inventory(fixture.sessionId())).isEqualTo(new InventoryState(4, 3, 1, 0));
        assertInventoryInvariant(fixture.sessionId());
    }

    @Test
    void rollsBackInsertedOrderWhenInventoryIsSoldOut() {
        long userId = createUser("sold-out-user");
        Fixture fixture = createFixture("sold-out", "PUBLISHED", Window.OPEN, 0);

        assertThatThrownBy(() -> reservationApi.reserve(userId, fixture.sessionId()))
                .isInstanceOfSatisfying(InventoryException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(InventoryErrorCode.SOLD_OUT));

        assertThat(orderCount(fixture.sessionId())).isZero();
        assertThat(inventory(fixture.sessionId())).isEqualTo(new InventoryState(0, 0, 0, 0));
    }

    @Test
    void rollsBackInsertedOrderWhenInventoryIsNotConfigured() {
        long userId = createUser("missing-inventory-user");
        Fixture fixture = createFixture("missing-inventory", "PUBLISHED", Window.OPEN, null);

        assertThatThrownBy(() -> reservationApi.reserve(userId, fixture.sessionId()))
                .isInstanceOfSatisfying(InventoryException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(InventoryErrorCode.INVENTORY_NOT_CONFIGURED));

        assertThat(orderCount(fixture.sessionId())).isZero();
    }

    @Test
    void allowsNewWaitPayAfterClosedHistory() {
        assertHistoricalOrderAllowsReservation("closed", "CLOSED");
    }

    @Test
    void allowsNewWaitPayAfterCancelledHistory() {
        assertHistoricalOrderAllowsReservation("cancelled", "CANCELLED");
    }

    @Test
    void paidOrderBlocksReservationThroughEffectivePurchaseConstraint() {
        long userId = createUser("paid-user");
        Fixture fixture = createFixture("paid", "PUBLISHED", Window.OPEN, 3);
        insertHistoricalOrder(userId, fixture.sessionId(), "PAID", "paid");

        assertThatThrownBy(() -> reservationApi.reserve(userId, fixture.sessionId()))
                .isInstanceOfSatisfying(OrderException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(OrderErrorCode.ACTIVE_PURCHASE_EXISTS));

        assertThat(activeOrderCount(userId, fixture.sessionId())).isEqualTo(1);
        assertThat(inventory(fixture.sessionId())).isEqualTo(new InventoryState(3, 3, 0, 0));
    }

    @Test
    void enforcesPublishedVisibilityAndSaleWindow() {
        long userId = createUser("visibility-user");
        Fixture draft = createFixture("draft", "DRAFT", Window.OPEN, 1);
        Fixture before = createFixture("before", "PUBLISHED", Window.BEFORE, 1);
        Fixture ended = createFixture("ended", "PUBLISHED", Window.ENDED, 1);

        assertThatThrownBy(() -> reservationApi.reserve(userId, draft.sessionId()))
                .isInstanceOfSatisfying(EventException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(EventErrorCode.SESSION_NOT_FOUND));
        assertThatThrownBy(() -> reservationApi.reserve(userId, before.sessionId()))
                .isInstanceOfSatisfying(OrderException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(OrderErrorCode.SALE_NOT_STARTED));
        assertThatThrownBy(() -> reservationApi.reserve(userId, ended.sessionId()))
                .isInstanceOfSatisfying(OrderException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(OrderErrorCode.SALE_ENDED));

        assertThat(orderCount(draft.sessionId())).isZero();
        assertThat(orderCount(before.sessionId())).isZero();
        assertThat(orderCount(ended.sessionId())).isZero();
    }

    @Test
    void usesJwtPrincipalAndReturnsSafeOrderResponseWithoutRequestBody() throws Exception {
        Identity identity = register("http-success");
        Fixture fixture = createFixture("http-success", "PUBLISHED", Window.OPEN, 2);

        mockMvc.perform(post("/api/sessions/{id}/orders", fixture.sessionId()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/sessions/{id}", fixture.sessionId()))
                .andExpect(status().isOk());

        String responseBody = mockMvc.perform(post("/api/sessions/{id}/orders", fixture.sessionId())
                        .header("Authorization", bearer(identity))
                        .header("X-User-Id", "999999")
                        .queryParam("userId", "999999"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("WAIT_PAY"))
                .andExpect(jsonPath("$.sessionId").value(fixture.sessionId()))
                .andExpect(jsonPath("$.userId").doesNotExist())
                .andExpect(jsonPath("$.purchaseGuard").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        JsonNode response = objectMapper.readTree(responseBody);
        assertThat(orderUserId(response.get("id").asLong())).isEqualTo(identity.profile().getId());
    }

    @Test
    void mapsAllReservationBusinessFailuresToFrozenHttpStatuses() throws Exception {
        Identity identity = register("http-errors");
        long userId = identity.profile().getId();
        String authorization = bearer(identity);

        Fixture draft = createFixture("http-draft", "DRAFT", Window.OPEN, 1);
        postExpecting(authorization, draft.sessionId(), 404, "SESSION_NOT_FOUND");

        Fixture before = createFixture("http-before", "PUBLISHED", Window.BEFORE, 1);
        postExpecting(authorization, before.sessionId(), 409, "SALE_NOT_STARTED");

        Fixture ended = createFixture("http-ended", "PUBLISHED", Window.ENDED, 1);
        postExpecting(authorization, ended.sessionId(), 409, "SALE_ENDED");

        Fixture soldOut = createFixture("http-sold-out", "PUBLISHED", Window.OPEN, 0);
        postExpecting(authorization, soldOut.sessionId(), 409, "SOLD_OUT");
        assertThat(orderCount(soldOut.sessionId())).isZero();

        Fixture missing = createFixture("http-missing", "PUBLISHED", Window.OPEN, null);
        postExpecting(authorization, missing.sessionId(), 409, "INVENTORY_NOT_CONFIGURED");
        assertThat(orderCount(missing.sessionId())).isZero();

        Fixture duplicate = createFixture("http-duplicate", "PUBLISHED", Window.OPEN, 2);
        reservationApi.reserve(userId, duplicate.sessionId());
        postExpecting(authorization, duplicate.sessionId(), 409, "ACTIVE_PURCHASE_EXISTS");
        assertThat(activeOrderCount(userId, duplicate.sessionId())).isEqualTo(1);
        assertThat(inventory(duplicate.sessionId())).isEqualTo(new InventoryState(2, 1, 1, 0));
    }

    @AfterEach
    void cleanupAndVerifyNoSyntheticResidue() {
        jdbcTemplate.update("DELETE o FROM ticket_order o JOIN event_sessions s ON s.id = o.session_id "
                + "WHERE s.name LIKE ?", TEST_PREFIX + "%");
        jdbcTemplate.update("DELETE i FROM event_inventory i JOIN event_sessions s ON s.id = i.session_id "
                + "WHERE s.name LIKE ?", TEST_PREFIX + "%");
        assertThat(count("SELECT COUNT(*) FROM ticket_order o JOIN event_sessions s ON s.id = o.session_id "
                + "WHERE s.name LIKE ?", TEST_PREFIX + "%")).isZero();
        assertThat(count("SELECT COUNT(*) FROM event_inventory i JOIN event_sessions s ON s.id = i.session_id "
                + "WHERE s.name LIKE ?", TEST_PREFIX + "%")).isZero();

        jdbcTemplate.update("DELETE FROM event_sessions WHERE name LIKE ?", TEST_PREFIX + "%");
        jdbcTemplate.update("DELETE FROM events WHERE title LIKE ?", TEST_PREFIX + "%");
        jdbcTemplate.update("DELETE FROM users WHERE username LIKE ?", TEST_PREFIX + "%");

        assertThat(count("SELECT COUNT(*) FROM event_sessions WHERE name LIKE ?", TEST_PREFIX + "%")).isZero();
        assertThat(count("SELECT COUNT(*) FROM events WHERE title LIKE ?", TEST_PREFIX + "%")).isZero();
        assertThat(count("SELECT COUNT(*) FROM users WHERE username LIKE ?", TEST_PREFIX + "%")).isZero();
    }

    private void assertHistoricalOrderAllowsReservation(String scenario, String historicalStatus) {
        long userId = createUser(scenario + "-user");
        Fixture fixture = createFixture(scenario, "PUBLISHED", Window.OPEN, 3);
        insertHistoricalOrder(userId, fixture.sessionId(), historicalStatus, scenario);

        var created = reservationApi.reserve(userId, fixture.sessionId());

        assertThat(created.status()).isEqualTo(OrderStatus.WAIT_PAY);
        assertThat(orderCount(fixture.sessionId())).isEqualTo(2);
        assertThat(activeOrderCount(userId, fixture.sessionId())).isEqualTo(1);
        assertThat(inventory(fixture.sessionId())).isEqualTo(new InventoryState(3, 2, 1, 0));
    }

    private void postExpecting(String authorization, long sessionId, int httpStatus, String errorCode)
            throws Exception {
        mockMvc.perform(post("/api/sessions/{id}/orders", sessionId)
                        .header("Authorization", authorization))
                .andExpect(status().is(httpStatus))
                .andExpect(jsonPath("$.code").value(errorCode));
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

    private Fixture createFixture(String scenario, String eventStatus, Window window, Integer stock) {
        String suffix = scenario + '-' + shortId();
        String title = TEST_PREFIX + suffix;
        jdbcTemplate.update("INSERT INTO events (organizer_user_id, title, status) VALUES (?, ?, ?)",
                9_602L, title, eventStatus);
        long eventId = jdbcTemplate.queryForObject("SELECT id FROM events WHERE title = ?", Long.class, title);

        LocalDateTime now = utcNow();
        LocalDateTime saleStart;
        LocalDateTime saleEnd;
        if (window == Window.BEFORE) {
            saleStart = now.plusHours(1);
            saleEnd = now.plusHours(2);
        } else if (window == Window.ENDED) {
            saleStart = now.minusHours(2);
            saleEnd = now.minusHours(1);
        } else {
            saleStart = now.minusMinutes(5);
            saleEnd = now.plusMinutes(5);
        }
        String sessionName = TEST_PREFIX + suffix;
        jdbcTemplate.update("INSERT INTO event_sessions "
                        + "(event_id, name, sale_start_at, sale_end_at, start_at, end_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                eventId, sessionName, saleStart, saleEnd, now.plusHours(3), now.plusHours(5));
        long sessionId = jdbcTemplate.queryForObject(
                "SELECT id FROM event_sessions WHERE name = ?", Long.class, sessionName);
        if (stock != null) {
            jdbcTemplate.update("INSERT INTO event_inventory "
                            + "(session_id, total_stock, available_stock, locked_stock, sold_stock) "
                            + "VALUES (?, ?, ?, 0, 0)",
                    sessionId, stock, stock);
        }
        return new Fixture(eventId, sessionId);
    }

    private void insertHistoricalOrder(long userId, long sessionId, String status, String scenario) {
        jdbcTemplate.update("INSERT INTO ticket_order "
                        + "(order_no, user_id, session_id, status, expire_at) VALUES (?, ?, ?, ?, ?)",
                orderNo(scenario), userId, sessionId, status, utcNow().plusMinutes(15));
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

    private long orderCount(long sessionId) {
        return count("SELECT COUNT(*) FROM ticket_order WHERE session_id = ?", sessionId);
    }

    private long activeOrderCount(long userId, long sessionId) {
        Long value = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ticket_order WHERE user_id = ? AND session_id = ? "
                        + "AND status IN ('WAIT_PAY', 'PAID')",
                Long.class, userId, sessionId);
        return value == null ? 0 : value;
    }

    private String orderStatus(long orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM ticket_order WHERE id = ?", String.class, orderId);
    }

    private long orderUserId(long orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT user_id FROM ticket_order WHERE id = ?", Long.class, orderId);
    }

    private long count(String sql, Object value) {
        Long count = jdbcTemplate.queryForObject(sql, Long.class, value);
        return count == null ? 0 : count;
    }

    private static LocalDateTime utcNow() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    private static String bearer(Identity identity) {
        return "Bearer " + identity.accessToken().getTokenValue();
    }

    private static String orderNo(String scenario) {
        return "TEST-" + scenario.toUpperCase() + '-' + UUID.randomUUID().toString().replace("-", "");
    }

    private static String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private enum Window {
        OPEN,
        BEFORE,
        ENDED
    }

    private record Fixture(long eventId, long sessionId) {
    }

    private record Identity(UserProfile profile, AccessToken accessToken) {
    }

    private record InventoryState(int total, int available, int locked, int sold) {
    }
}

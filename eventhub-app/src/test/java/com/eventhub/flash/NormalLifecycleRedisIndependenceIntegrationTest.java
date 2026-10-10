package com.eventhub.flash;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.eventhub.application.flash.FlashOrderLifecycleApplicationService;
import com.eventhub.auth.api.model.AccessTokenContract;
import com.eventhub.order.api.OrderTimeoutApi;
import com.eventhub.scheduler.OrderTimeoutScheduler;
import com.eventhub.user.api.model.UserRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class NormalLifecycleRedisIndependenceIntegrationTest {

    private static final String PREFIX = "phase10-normal-lifecycle-";
    private static final String ENCODED_PASSWORD = "{bcrypt}$2a$10$phase10.synthetic.credential";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private OrderTimeoutApi orderTimeoutApi;

    @Autowired
    private FlashOrderLifecycleApplicationService lifecycleService;

    private final List<Fixture> fixtures = new ArrayList<>();

    @AfterEach
    void cleanupAndVerifyNoResidue() {
        for (Fixture fixture : fixtures) {
            jdbcTemplate.update("DELETE FROM ticket WHERE order_id = ?", fixture.orderId());
            jdbcTemplate.update("DELETE FROM ticket_order WHERE id = ?", fixture.orderId());
            jdbcTemplate.update("DELETE FROM event_inventory WHERE session_id = ?", fixture.sessionId());
            jdbcTemplate.update("DELETE FROM event_sessions WHERE id = ?", fixture.sessionId());
            jdbcTemplate.update("DELETE FROM events WHERE id = ?", fixture.eventId());
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", fixture.userId());
        }
        assertThat(count("SELECT COUNT(*) FROM users WHERE username LIKE ?", PREFIX + "%")).isZero();
    }

    @Test
    void cancelTimeoutAndRefundSucceedWithoutAnyRedisLifecycleDependency() throws Exception {
        Fixture cancel = fixture("cancel", "WAIT_PAY", false);
        mockMvc.perform(post("/api/orders/{orderId}/cancel", cancel.orderId())
                        .header(HttpHeaders.AUTHORIZATION, cancel.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        assertState(cancel, "CANCELLED", 1, 0, 0);

        Fixture timeout = fixture("timeout", "WAIT_PAY", false);
        jdbcTemplate.update("UPDATE ticket_order SET expire_at = ? WHERE id = ?",
                LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1), timeout.orderId());
        new OrderTimeoutScheduler(orderTimeoutApi, lifecycleService, 100).processExpiredOrders();
        assertState(timeout, "CLOSED", 1, 0, 0);

        Fixture refund = fixture("refund", "PAID", true);
        mockMvc.perform(post("/api/orders/{orderId}/refund", refund.orderId())
                        .header(HttpHeaders.AUTHORIZATION, refund.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REFUNDED"));
        assertState(refund, "REFUNDED", 1, 0, 0);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM ticket WHERE order_id = ?", String.class, refund.orderId()))
                .isEqualTo("REFUNDED");
    }

    private Fixture fixture(String scenario, String orderStatus, boolean ticket) {
        String suffix = scenario + '-' + shortId();
        String username = PREFIX + suffix;
        jdbcTemplate.update("INSERT INTO users (username, password_hash, display_name, role) VALUES (?, ?, ?, 'USER')",
                username, ENCODED_PASSWORD, scenario);
        long userId = requiredLong("SELECT id FROM users WHERE username = ?", username);

        String title = PREFIX + "event-" + suffix;
        jdbcTemplate.update("INSERT INTO events (organizer_user_id, title, status) VALUES (?, ?, 'PUBLISHED')",
                userId, title);
        long eventId = requiredLong("SELECT id FROM events WHERE title = ?", title);

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        String sessionName = PREFIX + "session-" + suffix;
        jdbcTemplate.update("""
                INSERT INTO event_sessions (
                    event_id, name, sale_mode, sale_start_at, sale_end_at, start_at, end_at)
                VALUES (?, ?, 'NORMAL', ?, ?, ?, ?)
                """, eventId, sessionName, now.minusMinutes(5), now.plusMinutes(5),
                now.plusHours(1), now.plusHours(2));
        long sessionId = requiredLong("SELECT id FROM event_sessions WHERE name = ?", sessionName);
        int locked = orderStatus.equals("WAIT_PAY") ? 1 : 0;
        int sold = orderStatus.equals("PAID") ? 1 : 0;
        jdbcTemplate.update("""
                INSERT INTO event_inventory (
                    session_id, total_stock, available_stock, locked_stock, sold_stock)
                VALUES (?, 1, 0, ?, ?)
                """, sessionId, locked, sold);

        String orderNo = "EH-NORMAL-" + shortId();
        jdbcTemplate.update("""
                INSERT INTO ticket_order (order_no, user_id, session_id, status, expire_at)
                VALUES (?, ?, ?, ?, ?)
                """, orderNo, userId, sessionId, orderStatus, now.plusMinutes(15));
        long orderId = requiredLong("SELECT id FROM ticket_order WHERE order_no = ?", orderNo);
        if (ticket) {
            jdbcTemplate.update("""
                    INSERT INTO ticket (ticket_no, order_id, user_id, session_id, status)
                    VALUES (?, ?, ?, ?, 'VALID')
                    """, "TKT-" + UUID.randomUUID().toString().replace("-", "").toUpperCase(),
                    orderId, userId, sessionId);
        }
        Fixture fixture = new Fixture(userId, eventId, sessionId, orderId,
                bearerToken(userId, username));
        fixtures.add(fixture);
        return fixture;
    }

    private void assertState(Fixture fixture, String status, int available, int locked, int sold) {
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM ticket_order WHERE id = ?", String.class, fixture.orderId()))
                .isEqualTo(status);
        InventoryState inventory = jdbcTemplate.queryForObject(
                "SELECT total_stock, available_stock, locked_stock, sold_stock "
                        + "FROM event_inventory WHERE session_id = ?",
                (resultSet, rowNum) -> new InventoryState(
                        resultSet.getInt("total_stock"),
                        resultSet.getInt("available_stock"),
                        resultSet.getInt("locked_stock"),
                        resultSet.getInt("sold_stock")),
                fixture.sessionId());
        assertThat(inventory).isEqualTo(new InventoryState(1, available, locked, sold));
        assertThat(inventory.total()).isEqualTo(inventory.available() + inventory.locked() + inventory.sold());
    }

    private String bearerToken(long userId, String username) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(AccessTokenContract.ISSUER)
                .subject(Long.toString(userId))
                .issuedAt(now.minusSeconds(1))
                .expiresAt(now.plus(AccessTokenContract.TIME_TO_LIVE))
                .claim(AccessTokenContract.USERNAME_CLAIM, username)
                .claim(AccessTokenContract.ROLE_CLAIM, UserRole.USER.name())
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).type("JWT").build();
        return "Bearer " + jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    private long requiredLong(String sql, Object... arguments) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, arguments);
        if (value == null) {
            throw new IllegalStateException("fixture id was not generated");
        }
        return value;
    }

    private long count(String sql, Object... arguments) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, arguments);
        return value == null ? 0 : value;
    }

    private static String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private record Fixture(long userId, long eventId, long sessionId, long orderId, String token) {
    }

    private record InventoryState(int total, int available, int locked, int sold) {
    }
}

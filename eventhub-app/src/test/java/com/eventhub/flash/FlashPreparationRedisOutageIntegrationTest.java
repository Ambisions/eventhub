package com.eventhub.flash;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.eventhub.auth.api.model.AccessTokenContract;
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

@SpringBootTest(properties = {
        "spring.data.redis.host=127.0.0.1",
        "spring.data.redis.port=1",
        "spring.data.redis.connect-timeout=250ms",
        "spring.data.redis.timeout=250ms"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FlashPreparationRedisOutageIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JwtEncoder jwtEncoder;

    private final List<Long> eventIds = new ArrayList<>();
    private final List<Long> sessionIds = new ArrayList<>();

    @AfterEach
    void cleanup() {
        if (!sessionIds.isEmpty()) {
            jdbcTemplate.batchUpdate(
                    "DELETE FROM ticket_order WHERE session_id = ?",
                    sessionIds.stream().map(id -> new Object[]{id}).toList());
            jdbcTemplate.batchUpdate(
                    "DELETE FROM event_inventory WHERE session_id = ?",
                    sessionIds.stream().map(id -> new Object[]{id}).toList());
            jdbcTemplate.batchUpdate(
                    "DELETE FROM event_sessions WHERE id = ?",
                    sessionIds.stream().map(id -> new Object[]{id}).toList());
        }
        if (!eventIds.isEmpty()) {
            jdbcTemplate.batchUpdate(
                    "DELETE FROM events WHERE id = ?",
                    eventIds.stream().map(id -> new Object[]{id}).toList());
        }
    }

    @Test
    void returnsSanitizedServiceUnavailableWhenRedisConnectionFails() throws Exception {
        Fixture fixture = fixture();

        String response = mockMvc.perform(post("/api/events/{eventId}/sessions/{sessionId}/flash/prepare",
                                fixture.eventId(), fixture.sessionId())
                        .header(HttpHeaders.AUTHORIZATION, bearerToken()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("FLASH_SERVICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("FLASH service is unavailable"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(response)
                .doesNotContain("127.0.0.1", "6379", "RedisConnection", "DataAccess", "Exception");

        String reservationResponse = mockMvc.perform(post("/api/sessions/{sessionId}/flash/reserve",
                                fixture.sessionId())
                        .header(HttpHeaders.AUTHORIZATION, bearerToken()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("FLASH_SERVICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("FLASH service is unavailable"))
                .andReturn().getResponse().getContentAsString();
        assertThat(reservationResponse)
                .doesNotContain("127.0.0.1", "6379", "RedisConnection", "DataAccess", "Exception");
        assertThat(orderCount(fixture.sessionId())).isZero();
    }

    @Test
    void normalReservationRemainsIndependentWhenRedisIsUnavailable() throws Exception {
        Fixture fixture = normalFixture();

        mockMvc.perform(post("/api/sessions/{sessionId}/orders", fixture.sessionId())
                        .header(HttpHeaders.AUTHORIZATION, bearerToken()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("WAIT_PAY"));

        assertThat(orderCount(fixture.sessionId())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT available_stock FROM event_inventory WHERE session_id = ?",
                Integer.class,
                fixture.sessionId())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT locked_stock FROM event_inventory WHERE session_id = ?",
                Integer.class,
                fixture.sessionId())).isEqualTo(1);
    }

    private Fixture fixture() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String title = "phase10-outage-" + suffix;
        jdbcTemplate.update(
                "INSERT INTO events (organizer_user_id, title, status) VALUES (?, ?, 'PUBLISHED')",
                9901L, title);
        long eventId = jdbcTemplate.queryForObject(
                "SELECT id FROM events WHERE title = ?", Long.class, title);
        eventIds.add(eventId);

        LocalDateTime saleStart = LocalDateTime.now(ZoneOffset.UTC).plusMinutes(10);
        String sessionName = "phase10-outage-session-" + suffix;
        jdbcTemplate.update("""
                INSERT INTO event_sessions (
                    event_id, name, sale_mode, sale_start_at, sale_end_at, start_at, end_at)
                VALUES (?, ?, 'FLASH', ?, ?, ?, ?)
                """, eventId, sessionName, saleStart, saleStart.plusHours(1),
                saleStart.plusHours(2), saleStart.plusHours(3));
        long sessionId = jdbcTemplate.queryForObject(
                "SELECT id FROM event_sessions WHERE name = ?", Long.class, sessionName);
        sessionIds.add(sessionId);
        jdbcTemplate.update("""
                INSERT INTO event_inventory (
                    session_id, total_stock, available_stock, locked_stock, sold_stock)
                VALUES (?, 2, 2, 0, 0)
                """, sessionId);
        return new Fixture(eventId, sessionId);
    }

    private Fixture normalFixture() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String title = "phase10-outage-normal-" + suffix;
        jdbcTemplate.update(
                "INSERT INTO events (organizer_user_id, title, status) VALUES (?, ?, 'PUBLISHED')",
                9901L, title);
        long eventId = jdbcTemplate.queryForObject(
                "SELECT id FROM events WHERE title = ?", Long.class, title);
        eventIds.add(eventId);

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        String sessionName = "phase10-outage-normal-session-" + suffix;
        jdbcTemplate.update("""
                INSERT INTO event_sessions (
                    event_id, name, sale_mode, sale_start_at, sale_end_at, start_at, end_at)
                VALUES (?, ?, 'NORMAL', ?, ?, ?, ?)
                """, eventId, sessionName, now.minusMinutes(5), now.plusMinutes(5),
                now.plusHours(2), now.plusHours(3));
        long sessionId = jdbcTemplate.queryForObject(
                "SELECT id FROM event_sessions WHERE name = ?", Long.class, sessionName);
        sessionIds.add(sessionId);
        jdbcTemplate.update("""
                INSERT INTO event_inventory (
                    session_id, total_stock, available_stock, locked_stock, sold_stock)
                VALUES (?, 2, 2, 0, 0)
                """, sessionId);
        return new Fixture(eventId, sessionId);
    }

    private long orderCount(long sessionId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ticket_order WHERE session_id = ?", Long.class, sessionId);
        return count == null ? 0L : count;
    }

    private String bearerToken() {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(AccessTokenContract.ISSUER)
                .subject("9901")
                .issuedAt(now.minusSeconds(1))
                .expiresAt(now.plus(AccessTokenContract.TIME_TO_LIVE))
                .claim(AccessTokenContract.USERNAME_CLAIM, "phase10-outage-admin")
                .claim(AccessTokenContract.ROLE_CLAIM, UserRole.ADMIN.name())
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).type("JWT").build();
        return "Bearer " + jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    private record Fixture(long eventId, long sessionId) {
    }
}

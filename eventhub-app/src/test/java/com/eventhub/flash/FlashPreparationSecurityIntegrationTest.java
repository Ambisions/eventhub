package com.eventhub.flash;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.eventhub.auth.api.model.AccessTokenContract;
import com.eventhub.user.api.model.UserRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FlashPreparationSecurityIntegrationTest.FlashPreparationTestConfiguration.class)
class FlashPreparationSecurityIntegrationTest {

    private static final Instant APP_NOW = Instant.parse("2032-01-01T00:00:00.123456Z");
    private static final LocalDateTime SALE_START = LocalDateTime.ofInstant(
            APP_NOW.plusSeconds(600), ZoneOffset.UTC);
    private static final LocalDateTime SALE_END = SALE_START.plusHours(1);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private MutableClock preparationClock;

    private final List<Long> eventIds = new ArrayList<>();
    private final List<Long> sessionIds = new ArrayList<>();

    @BeforeEach
    void resetClock() {
        preparationClock.setInstant(APP_NOW);
    }

    @AfterEach
    void cleanup() {
        for (long sessionId : sessionIds) {
            redisTemplate.delete(List.of(metaKey(sessionId), admissionsKey(sessionId)));
        }
        if (!sessionIds.isEmpty()) {
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
    void enforcesAnonymousUserOwnerAndAdminSecurityThenPreparesEligibleSession() throws Exception {
        Fixture fixture = fixture("security", "PUBLISHED", "FLASH", 7, 7, 0, 0);
        String path = path(fixture);

        mockMvc.perform(post(path)).andExpect(status().isUnauthorized());
        assertNoRedisState(fixture.sessionId());

        mockMvc.perform(post(path)
                        .header(HttpHeaders.AUTHORIZATION,
                                bearerToken(fixture.organizerId(), UserRole.USER)))
                .andExpect(status().isForbidden());
        assertNoRedisState(fixture.sessionId());

        mockMvc.perform(post(path)
                        .header(HttpHeaders.AUTHORIZATION,
                                bearerToken(fixture.organizerId() + 1, UserRole.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value(fixture.sessionId()))
                .andExpect(jsonPath("$.state").value("PREPARED"));

        assertThat(redisTemplate.opsForHash().entries(metaKey(fixture.sessionId())))
                .containsEntry("state", "PREPARED")
                .containsEntry("capacity", "7")
                .containsEntry("stock", "7")
                .containsEntry("saleStartUs", Long.toString(epochMicros(SALE_START)))
                .containsEntry("saleEndUs", Long.toString(epochMicros(SALE_END)));
        assertThat(redisTemplate.hasKey(admissionsKey(fixture.sessionId()))).isFalse();

        mockMvc.perform(post(path)
                        .header(HttpHeaders.AUTHORIZATION,
                                bearerToken(fixture.organizerId() + 1, UserRole.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("PREPARED"));
        assertThat(redisTemplate.opsForHash().get(metaKey(fixture.sessionId()), "stock"))
                .isEqualTo("7");
    }

    @Test
    void appliesBusinessEligibilityPriorityWithoutPartialRedisInitialization() throws Exception {
        String admin = bearerToken(9001, UserRole.ADMIN);

        mockMvc.perform(post("/api/events/999999999/sessions/999999998/flash/prepare")
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("FLASH_PREPARATION_NOT_FOUND"));

        Fixture draft = fixture("draft", "DRAFT", "FLASH", 3, 3, 0, 0);
        assertRejectedWithoutRedis(draft, admin, 409, "FLASH_PREPARATION_NOT_ALLOWED");

        Fixture normal = fixture("normal", "PUBLISHED", "NORMAL", 3, 3, 0, 0);
        assertRejectedWithoutRedis(normal, admin, 409, "FLASH_PREPARATION_NOT_ALLOWED");

        Fixture missingInventory = fixture("missing-inventory", "PUBLISHED", "FLASH", null, null, null, null);
        assertRejectedWithoutRedis(missingInventory, admin, 404, "FLASH_PREPARATION_NOT_FOUND");

        Fixture locked = fixture("locked", "PUBLISHED", "FLASH", 3, 2, 1, 0);
        assertRejectedWithoutRedis(locked, admin, 409, "FLASH_PREPARATION_NOT_ALLOWED");

        Fixture sold = fixture("sold", "PUBLISHED", "FLASH", 3, 2, 0, 1);
        assertRejectedWithoutRedis(sold, admin, 409, "FLASH_PREPARATION_NOT_ALLOWED");

        Fixture exactStart = fixture("exact-start", "PUBLISHED", "FLASH", 3, 3, 0, 0);
        preparationClock.setInstant(SALE_START.toInstant(ZoneOffset.UTC));
        assertRejectedWithoutRedis(exactStart, admin, 409, "FLASH_PREPARATION_WINDOW_CLOSED");
    }

    @Test
    void mapsRedisStateConflictWithoutRepairingExistingState() throws Exception {
        Fixture fixture = fixture("state-conflict", "PUBLISHED", "FLASH", 4, 4, 0, 0);
        redisTemplate.opsForHash().putAll(metaKey(fixture.sessionId()), java.util.Map.of(
                "state", "PREPARED",
                "capacity", "9",
                "stock", "9",
                "saleStartUs", "1",
                "saleEndUs", "2"));
        java.util.Map<Object, Object> before = redisTemplate.opsForHash().entries(metaKey(fixture.sessionId()));

        mockMvc.perform(post(path(fixture))
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(9002, UserRole.ADMIN)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FLASH_PREPARATION_STATE_CONFLICT"));

        assertThat(redisTemplate.opsForHash().entries(metaKey(fixture.sessionId()))).isEqualTo(before);
    }

    private void assertRejectedWithoutRedis(
            Fixture fixture, String authorization, int expectedStatus, String expectedCode) throws Exception {
        mockMvc.perform(post(path(fixture)).header(HttpHeaders.AUTHORIZATION, authorization))
                .andExpect(status().is(expectedStatus))
                .andExpect(jsonPath("$.code").value(expectedCode));
        assertNoRedisState(fixture.sessionId());
    }

    private void assertNoRedisState(long sessionId) {
        assertThat(redisTemplate.hasKey(metaKey(sessionId))).isFalse();
        assertThat(redisTemplate.hasKey(admissionsKey(sessionId))).isFalse();
    }

    private Fixture fixture(
            String scenario,
            String eventStatus,
            String saleMode,
            Integer total,
            Integer available,
            Integer locked,
            Integer sold) {
        String suffix = scenario + '-' + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        long organizerId = Math.abs(UUID.randomUUID().getMostSignificantBits() >>> 1) + 1;
        String title = "phase10-flash-" + suffix;
        jdbcTemplate.update(
                "INSERT INTO events (organizer_user_id, title, status) VALUES (?, ?, ?)",
                organizerId, title, eventStatus);
        long eventId = jdbcTemplate.queryForObject(
                "SELECT id FROM events WHERE title = ?", Long.class, title);
        eventIds.add(eventId);

        String sessionName = "phase10-flash-session-" + suffix;
        jdbcTemplate.update("""
                INSERT INTO event_sessions (
                    event_id, name, sale_mode, sale_start_at, sale_end_at, start_at, end_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, eventId, sessionName, saleMode, SALE_START, SALE_END,
                SALE_END.plusHours(1), SALE_END.plusHours(2));
        long sessionId = jdbcTemplate.queryForObject(
                "SELECT id FROM event_sessions WHERE name = ?", Long.class, sessionName);
        sessionIds.add(sessionId);

        if (total != null) {
            jdbcTemplate.update("""
                    INSERT INTO event_inventory (
                        session_id, total_stock, available_stock, locked_stock, sold_stock)
                    VALUES (?, ?, ?, ?, ?)
                    """, sessionId, total, available, locked, sold);
        }
        return new Fixture(eventId, sessionId, organizerId);
    }

    private String bearerToken(long userId, UserRole role) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(AccessTokenContract.ISSUER)
                .subject(Long.toString(userId))
                .issuedAt(now.minusSeconds(1))
                .expiresAt(now.plus(AccessTokenContract.TIME_TO_LIVE))
                .claim(AccessTokenContract.USERNAME_CLAIM, "phase10-user-" + userId)
                .claim(AccessTokenContract.ROLE_CLAIM, role.name())
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).type("JWT").build();
        return "Bearer " + jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    private static String path(Fixture fixture) {
        return "/api/events/" + fixture.eventId()
                + "/sessions/" + fixture.sessionId() + "/flash/prepare";
    }

    private static long epochMicros(LocalDateTime value) {
        Instant instant = value.toInstant(ZoneOffset.UTC);
        return Math.addExact(
                Math.multiplyExact(instant.getEpochSecond(), 1_000_000L),
                instant.getNano() / 1_000L);
    }

    private static String metaKey(long sessionId) {
        return "eventhub:flash:{" + sessionId + "}:meta";
    }

    private static String admissionsKey(long sessionId) {
        return "eventhub:flash:{" + sessionId + "}:admissions";
    }

    private record Fixture(long eventId, long sessionId, long organizerId) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FlashPreparationTestConfiguration {

        @Bean
        @Primary
        MutableClock flashPreparationClock() {
            return new MutableClock(APP_NOW);
        }
    }

    static final class MutableClock extends Clock {

        private final AtomicReference<Instant> current;

        private MutableClock(Instant initial) {
            current = new AtomicReference<>(initial);
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
}

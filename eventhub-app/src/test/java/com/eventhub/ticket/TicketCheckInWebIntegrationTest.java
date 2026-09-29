package com.eventhub.ticket;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import com.eventhub.application.checkin.CheckInApplicationService;
import com.eventhub.auth.api.model.AccessTokenContract;
import com.eventhub.ticket.api.model.TicketCheckInResult;
import com.eventhub.ticket.api.model.TicketStatus;
import com.eventhub.user.api.model.UserRole;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TicketCheckInWebIntegrationTest.CheckInClockConfiguration.class)
class TicketCheckInWebIntegrationTest {

    private static final AtomicLong SYNTHETIC_ID = new AtomicLong(8_300_000_000L);
    private static final Instant WINDOW_INSTANT = Instant.parse("2030-06-01T10:00:00Z");
    private static final LocalDateTime WINDOW_TIME = LocalDateTime.ofInstant(WINDOW_INSTANT, ZoneOffset.UTC);
    private static final Set<String> RESPONSE_FIELDS = Set.of("ticketNo", "sessionId", "status");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CheckInApplicationService checkInApplicationService;

    @Autowired
    private MutableClock checkInClock;

    private final List<String> ticketNumbers = new ArrayList<>();
    private final List<Long> sessionIds = new ArrayList<>();
    private final List<Long> eventIds = new ArrayList<>();

    @BeforeEach
    void resetClock() {
        checkInClock.setInstant(WINDOW_INSTANT);
    }

    @Test
    void applicationServiceOwnsTransactionAndDrivesMandatoryMutation() {
        long ownerId = nextId();
        Fixture fixture = fixture(ownerId, nextId(), "VALID", WINDOW_TIME.minusHours(1), WINDOW_TIME.plusHours(1));

        TicketCheckInResult result = checkInApplicationService.checkIn(ownerId, fixture.ticketNo());

        assertThat(result.ticketNo()).isEqualTo(fixture.ticketNo());
        assertThat(result.sessionId()).isEqualTo(fixture.sessionId());
        assertThat(result.status()).isEqualTo(TicketStatus.USED);
        assertThat(ticketStatus(fixture.ticketNo())).isEqualTo("USED");
    }

    @Test
    void requiresAuthenticationWithoutMutatingTicket() throws Exception {
        Fixture fixture = fixture(nextId(), nextId(), "VALID", WINDOW_TIME.minusHours(1), WINDOW_TIME.plusHours(1));

        mockMvc.perform(post("/api/tickets/{ticketNo}/check-in", fixture.ticketNo()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        assertThat(ticketStatus(fixture.ticketNo())).isEqualTo("VALID");
    }

    @Test
    void hidesInvalidAndMissingTicketsBehindSameNotFoundContract() throws Exception {
        String token = bearerToken(nextId(), UserRole.USER);

        assertCheckInError("invalid-ticket", token, HttpStatus.NOT_FOUND, "TICKET_NOT_FOUND");
        assertCheckInError(syntheticTicketNo(), token, HttpStatus.NOT_FOUND, "TICKET_NOT_FOUND");
    }

    @Test
    void hidesUsedAndClosedTicketsFromNonOwnerUserAndAdmin() throws Exception {
        long ownerId = nextId();
        Fixture fixture = fixture(ownerId, nextId(), "USED", WINDOW_TIME.minusHours(1), WINDOW_TIME.plusHours(1));
        assertThat(ticketExists(fixture.ticketNo())).isTrue();
        assertThat(eventOrganizer(fixture.eventId())).isEqualTo(ownerId);

        checkInClock.setInstant(WINDOW_INSTANT);
        assertCheckInError(
                fixture.ticketNo(), bearerToken(nextId(), UserRole.USER),
                HttpStatus.NOT_FOUND, "TICKET_NOT_FOUND");
        assertCheckInError(
                fixture.ticketNo(), bearerToken(nextId(), UserRole.ADMIN),
                HttpStatus.NOT_FOUND, "TICKET_NOT_FOUND");

        checkInClock.setInstant(WINDOW_INSTANT.plusSeconds(3_600));
        assertCheckInError(
                fixture.ticketNo(), bearerToken(nextId(), UserRole.USER),
                HttpStatus.NOT_FOUND, "TICKET_NOT_FOUND");
        assertThat(ticketStatus(fixture.ticketNo())).isEqualTo("USED");
    }

    @Test
    void allowsAdminOwnerAndReturnsExactUsedResponse() throws Exception {
        long adminOwnerId = nextId();
        Fixture fixture = fixture(
                adminOwnerId, nextId(), "VALID", WINDOW_TIME.minusHours(1), WINDOW_TIME.plusHours(1));

        String response = mockMvc.perform(post("/api/tickets/{ticketNo}/check-in", fixture.ticketNo())
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(adminOwnerId, UserRole.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ticketNo").value(fixture.ticketNo()))
                .andExpect(jsonPath("$.sessionId").value(fixture.sessionId()))
                .andExpect(jsonPath("$.status").value("USED"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode body = objectMapper.readTree(response);
        Set<String> actualFields = new HashSet<>();
        body.fieldNames().forEachRemaining(actualFields::add);
        assertThat(actualFields).isEqualTo(RESPONSE_FIELDS);
        assertThat(ticketStatus(fixture.ticketNo())).isEqualTo("USED");
    }

    @Test
    void enforcesExactStartInsideExactEndAndAfterEndBoundaries() throws Exception {
        long ownerId = nextId();
        String token = bearerToken(ownerId, UserRole.USER);
        LocalDateTime start = WINDOW_TIME;
        LocalDateTime end = WINDOW_TIME.plusHours(1);

        Fixture startFixture = fixture(ownerId, nextId(), "VALID", start, end);
        checkInClock.setInstant(WINDOW_INSTANT.minusNanos(1_000));
        assertCheckInError(
                startFixture.ticketNo(), token,
                HttpStatus.CONFLICT, "TICKET_CHECK_IN_NOT_STARTED");
        assertThat(ticketStatus(startFixture.ticketNo())).isEqualTo("VALID");

        checkInClock.setInstant(WINDOW_INSTANT);
        assertCheckInSuccess(startFixture, token);

        Fixture insideFixture = fixture(ownerId, nextId(), "VALID", start, end);
        checkInClock.setInstant(WINDOW_INSTANT.plusSeconds(1));
        assertCheckInSuccess(insideFixture, token);

        Fixture endFixture = fixture(ownerId, nextId(), "VALID", start, end);
        checkInClock.setInstant(WINDOW_INSTANT.plusSeconds(3_600));
        assertCheckInError(
                endFixture.ticketNo(), token,
                HttpStatus.CONFLICT, "TICKET_CHECK_IN_CLOSED");
        assertThat(ticketStatus(endFixture.ticketNo())).isEqualTo("VALID");

        checkInClock.setInstant(WINDOW_INSTANT.plusSeconds(3_601));
        assertCheckInError(
                endFixture.ticketNo(), token,
                HttpStatus.CONFLICT, "TICKET_CHECK_IN_CLOSED");
        assertThat(ticketStatus(endFixture.ticketNo())).isEqualTo("VALID");
    }

    @Test
    void appliesTimeWindowBeforeUsedConflictForOwner() throws Exception {
        long ownerId = nextId();
        String token = bearerToken(ownerId, UserRole.USER);
        LocalDateTime start = WINDOW_TIME.minusHours(1);
        LocalDateTime end = WINDOW_TIME.plusHours(1);

        Fixture usedInside = fixture(ownerId, nextId(), "USED", start, end);
        checkInClock.setInstant(WINDOW_INSTANT);
        assertCheckInError(
                usedInside.ticketNo(), token,
                HttpStatus.CONFLICT, "TICKET_ALREADY_USED");

        Fixture usedClosed = fixture(ownerId, nextId(), "USED", start, end);
        checkInClock.setInstant(WINDOW_INSTANT.plusSeconds(3_600));
        assertCheckInError(
                usedClosed.ticketNo(), token,
                HttpStatus.CONFLICT, "TICKET_CHECK_IN_CLOSED");

        assertThat(ticketStatus(usedInside.ticketNo())).isEqualTo("USED");
        assertThat(ticketStatus(usedClosed.ticketNo())).isEqualTo("USED");
    }

    @Test
    void keepsSecuritySurfaceNarrowAndPreservesOwnerQuery() throws Exception {
        long ticketOwnerId = nextId();
        Fixture fixture = fixture(
                nextId(), ticketOwnerId, "VALID", WINDOW_TIME.minusHours(1), WINDOW_TIME.plusHours(1));
        String token = bearerToken(ticketOwnerId, UserRole.USER);

        mockMvc.perform(get("/api/tickets/{ticketNo}", fixture.ticketNo())
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/tickets").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/tickets/{ticketNo}", fixture.ticketNo())
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/tickets/a/b/check-in").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/tickets/{ticketNo}/check-in/extra", fixture.ticketNo())
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/tickets/{ticketNo}/check-in", fixture.ticketNo())
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/tickets/{ticketNo}/check-in", fixture.ticketNo())
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isForbidden());

        assertThat(ticketStatus(fixture.ticketNo())).isEqualTo("VALID");
    }

    @AfterEach
    void removeSyntheticFixturesAndVerifyResidue() {
        for (String ticketNo : ticketNumbers) {
            jdbcTemplate.update("DELETE FROM ticket WHERE ticket_no = ?", ticketNo);
        }
        for (Long sessionId : sessionIds) {
            jdbcTemplate.update("DELETE FROM event_sessions WHERE id = ?", sessionId);
        }
        for (Long eventId : eventIds) {
            jdbcTemplate.update("DELETE FROM events WHERE id = ?", eventId);
        }

        for (String ticketNo : ticketNumbers) {
            assertThat(ticketExists(ticketNo)).isFalse();
        }
        for (Long sessionId : sessionIds) {
            assertThat(countById("event_sessions", sessionId)).isZero();
        }
        for (Long eventId : eventIds) {
            assertThat(countById("events", eventId)).isZero();
        }
    }

    private Fixture fixture(
            long organizerUserId,
            long ticketUserId,
            String ticketStatus,
            LocalDateTime startAt,
            LocalDateTime endAt) {
        long eventId = nextId();
        long sessionId = nextId();
        long orderId = nextId();
        String ticketNo = syntheticTicketNo();
        eventIds.add(eventId);
        sessionIds.add(sessionId);
        ticketNumbers.add(ticketNo);

        jdbcTemplate.update("""
                INSERT INTO events (id, organizer_user_id, title, description, status)
                VALUES (?, ?, ?, ?, 'PUBLISHED')
                """, eventId, organizerUserId, "phase8-checkin-web-" + eventId, "synthetic check-in event");
        jdbcTemplate.update("""
                INSERT INTO event_sessions (
                    id, event_id, name, venue,
                    sale_start_at, sale_end_at, start_at, end_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                sessionId,
                eventId,
                "phase8-checkin-web-" + sessionId,
                "synthetic venue",
                startAt.minusDays(2),
                startAt.minusDays(1),
                startAt,
                endAt);
        jdbcTemplate.update("""
                INSERT INTO ticket (ticket_no, order_id, user_id, session_id, status)
                VALUES (?, ?, ?, ?, ?)
                """, ticketNo, orderId, ticketUserId, sessionId, ticketStatus);
        return new Fixture(eventId, sessionId, ticketNo);
    }

    private void assertCheckInSuccess(Fixture fixture, String token) throws Exception {
        mockMvc.perform(post("/api/tickets/{ticketNo}/check-in", fixture.ticketNo())
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("USED"));
        assertThat(ticketStatus(fixture.ticketNo())).isEqualTo("USED");
    }

    private void assertCheckInError(
            String ticketNo,
            String token,
            HttpStatus status,
            String code) throws Exception {
        mockMvc.perform(post("/api/tickets/{ticketNo}/check-in", ticketNo)
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                        .is(status.value()))
                .andExpect(jsonPath("$.code").value(code));
    }

    private String bearerToken(long userId, UserRole role) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(AccessTokenContract.ISSUER)
                .subject(Long.toString(userId))
                .issuedAt(now.minusSeconds(1))
                .expiresAt(now.plus(AccessTokenContract.TIME_TO_LIVE))
                .claim(AccessTokenContract.USERNAME_CLAIM, "phase8-checkin-" + userId)
                .claim(AccessTokenContract.ROLE_CLAIM, role.name())
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).type("JWT").build();
        return "Bearer " + jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    private String ticketStatus(String ticketNo) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM ticket WHERE ticket_no = ?",
                String.class,
                ticketNo);
    }

    private boolean ticketExists(String ticketNo) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ticket WHERE ticket_no = ?",
                Integer.class,
                ticketNo);
        return count != null && count == 1;
    }

    private long eventOrganizer(long eventId) {
        Long organizer = jdbcTemplate.queryForObject(
                "SELECT organizer_user_id FROM events WHERE id = ?",
                Long.class,
                eventId);
        return organizer == null ? -1 : organizer;
    }

    private int countById(String tableName, long id) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + tableName + " WHERE id = ?",
                Integer.class,
                id);
        return count == null ? -1 : count;
    }

    private static String syntheticTicketNo() {
        return "TKT-" + String.format("%032X", nextId());
    }

    private static long nextId() {
        return SYNTHETIC_ID.incrementAndGet();
    }

    private record Fixture(long eventId, long sessionId, String ticketNo) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class CheckInClockConfiguration {

        @Bean
        @Primary
        MutableClock checkInTestClock() {
            return new MutableClock(WINDOW_INSTANT);
        }
    }

    static final class MutableClock extends Clock {

        private final AtomicReference<Instant> instant;

        private MutableClock(Instant initialInstant) {
            this.instant = new AtomicReference<>(initialInstant);
        }

        void setInstant(Instant currentInstant) {
            instant.set(currentInstant);
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
            return instant.get();
        }
    }
}

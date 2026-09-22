package com.eventhub.ticket;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import com.eventhub.auth.api.model.AccessTokenContract;
import com.eventhub.user.api.model.UserRole;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.test.context.transaction.AfterTransaction;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

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
@Transactional
class TicketWebIntegrationTest {

    private static final AtomicLong SYNTHETIC_ID = new AtomicLong(7_400_000_000L);
    private static final Set<String> RESPONSE_FIELDS = Set.of(
            "ticketNo", "orderId", "sessionId", "status", "createdAt");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final Set<String> syntheticTicketNumbers = new HashSet<>();

    @Test
    void requiresAuthenticationForTicketQuery() throws Exception {
        TicketFixture ticket = insertTicket(nextId(), "VALID");

        mockMvc.perform(get("/api/tickets/{ticketNo}", ticket.ticketNo()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    void returnsOnlyFrozenResponseFieldsToOwner() throws Exception {
        long ownerId = nextId();
        TicketFixture ticket = insertTicket(ownerId, "USED");

        String response = mockMvc.perform(get("/api/tickets/{ticketNo}", ticket.ticketNo())
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(ownerId, UserRole.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ticketNo").value(ticket.ticketNo()))
                .andExpect(jsonPath("$.orderId").value(ticket.orderId()))
                .andExpect(jsonPath("$.sessionId").value(ticket.sessionId()))
                .andExpect(jsonPath("$.status").value("USED"))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode body = objectMapper.readTree(response);
        Set<String> actualFields = new HashSet<>();
        body.fieldNames().forEachRemaining(actualFields::add);
        assertThat(actualFields).isEqualTo(RESPONSE_FIELDS);
        assertThat(LocalDateTime.parse(body.get("createdAt").asText())).isEqualTo(ticket.createdAt());
    }

    @Test
    void hidesExistingTicketFromOtherUserAndAdminNonOwner() throws Exception {
        long ownerId = nextId();
        TicketFixture ticket = insertTicket(ownerId, "VALID");
        assertThat(ticketCount(ticket.ticketNo(), ownerId)).isEqualTo(1);

        assertNotFound(ticket.ticketNo(), bearerToken(nextId(), UserRole.USER));
        assertNotFound(ticket.ticketNo(), bearerToken(nextId(), UserRole.ADMIN));
    }

    @Test
    void returnsSameNotFoundErrorForMissingAndInvalidTicketNumbers() throws Exception {
        String ownerToken = bearerToken(nextId(), UserRole.USER);

        assertNotFound(syntheticTicketNo(), ownerToken);
        assertNotFound("invalid-ticket", ownerToken);
    }

    @Test
    void keepsUnsupportedTicketMethodsAndPathsDenied() throws Exception {
        String ticketNo = syntheticTicketNo();
        String authorization = bearerToken(nextId(), UserRole.USER);

        mockMvc.perform(post("/api/tickets/{ticketNo}", ticketNo)
                        .header(HttpHeaders.AUTHORIZATION, authorization))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(put("/api/tickets/{ticketNo}", ticketNo)
                        .header(HttpHeaders.AUTHORIZATION, authorization))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(delete("/api/tickets/{ticketNo}", ticketNo)
                        .header(HttpHeaders.AUTHORIZATION, authorization))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(get("/api/tickets")
                        .header(HttpHeaders.AUTHORIZATION, authorization))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(get("/api/tickets/a/b")
                        .header(HttpHeaders.AUTHORIZATION, authorization))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @AfterTransaction
    void verifiesTransactionRollbackRemovedSyntheticTickets() {
        for (String ticketNo : syntheticTicketNumbers) {
            Integer residue = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM ticket WHERE ticket_no = ?",
                    Integer.class,
                    ticketNo);
            assertThat(residue).isZero();
        }
    }

    private TicketFixture insertTicket(long userId, String status) {
        String ticketNo = syntheticTicketNo();
        long orderId = nextId();
        long sessionId = nextId();
        syntheticTicketNumbers.add(ticketNo);
        jdbcTemplate.update("""
                INSERT INTO ticket (ticket_no, order_id, user_id, session_id, status)
                VALUES (?, ?, ?, ?, ?)
                """, ticketNo, orderId, userId, sessionId, status);
        LocalDateTime createdAt = jdbcTemplate.queryForObject(
                "SELECT created_at FROM ticket WHERE ticket_no = ?",
                LocalDateTime.class,
                ticketNo);
        return new TicketFixture(ticketNo, orderId, userId, sessionId, status, createdAt);
    }

    private int ticketCount(String ticketNo, long userId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ticket WHERE ticket_no = ? AND user_id = ?",
                Integer.class,
                ticketNo,
                userId);
        return count == null ? -1 : count;
    }

    private void assertNotFound(String ticketNo, String authorization) throws Exception {
        mockMvc.perform(get("/api/tickets/{ticketNo}", ticketNo)
                        .header(HttpHeaders.AUTHORIZATION, authorization))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TICKET_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Ticket not found"));
    }

    private String bearerToken(long userId, UserRole role) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(AccessTokenContract.ISSUER)
                .subject(Long.toString(userId))
                .issuedAt(now.minusSeconds(1))
                .expiresAt(now.plus(AccessTokenContract.TIME_TO_LIVE))
                .claim(AccessTokenContract.USERNAME_CLAIM, "phase7-ticket-" + userId)
                .claim(AccessTokenContract.ROLE_CLAIM, role.name())
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).type("JWT").build();
        String token = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return "Bearer " + token;
    }

    private static String syntheticTicketNo() {
        return "TKT-" + String.format("%032X", nextId());
    }

    private static long nextId() {
        return SYNTHETIC_ID.incrementAndGet();
    }

    private record TicketFixture(
            String ticketNo,
            long orderId,
            long userId,
            long sessionId,
            String status,
            LocalDateTime createdAt) {
    }
}

package com.eventhub.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.UUID;

import com.eventhub.auth.api.model.AccessTokenContract;
import com.eventhub.notification.rabbit.NotificationRabbitTopology;
import com.eventhub.user.api.model.UserRole;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
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
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PaymentNotificationRabbitIntegrationTest {

    private static final String PREFIX = "phase11-payment-notification-";
    private static final String ENCODED_PASSWORD = "{bcrypt}$2a$10$phase11.synthetic.credential";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private ObjectMapper objectMapper;

    private Long userId;
    private Long eventId;
    private Long sessionId;
    private Long orderId;

    @BeforeEach
    void verifyQueueStartsEmpty() {
        assertThat(rabbitTemplate.receive(NotificationRabbitTopology.TICKET_ISSUED_QUEUE, 100)).isNull();
    }

    @Test
    void authenticatedHttpPayCommitsDatabaseThenPublishesOnePersistentTicketIssuedMessage() throws Exception {
        Fixture fixture = createFixture();

        MvcResult response = mockMvc.perform(post("/api/orders/{orderId}/pay", fixture.orderId())
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(fixture.userId(), fixture.username())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.order.id").value(fixture.orderId()))
                .andExpect(jsonPath("$.order.status").value("PAID"))
                .andExpect(jsonPath("$.ticket.orderId").value(fixture.orderId()))
                .andExpect(jsonPath("$.ticket.sessionId").value(fixture.sessionId()))
                .andExpect(jsonPath("$.ticket.status").value("VALID"))
                .andReturn();

        JsonNode responseJson = objectMapper.readTree(response.getResponse().getContentAsByteArray());
        String ticketNo = responseJson.at("/ticket/ticketNo").asText();
        assertThat(ticketNo).matches("^TKT-[0-9A-F]{32}$");
        assertThat(orderStatus(fixture.orderId())).isEqualTo("PAID");
        assertThat(inventory(fixture.sessionId())).isEqualTo(new InventoryState(1, 0, 0, 1));
        assertThat(ticketState(fixture.orderId()))
                .isEqualTo(new TicketState(ticketNo, fixture.userId(), fixture.sessionId(), "VALID"));

        Message message = rabbitTemplate.receive(NotificationRabbitTopology.TICKET_ISSUED_QUEUE, 5_000);
        assertThat(message).isNotNull();
        assertThat(message.getMessageProperties().getReceivedExchange())
                .isEqualTo(NotificationRabbitTopology.EXCHANGE);
        assertThat(message.getMessageProperties().getReceivedRoutingKey())
                .isEqualTo(NotificationRabbitTopology.TICKET_ISSUED_ROUTING_KEY);
        assertThat(message.getMessageProperties().getContentType()).isEqualTo(MessageProperties.CONTENT_TYPE_JSON);
        assertThat(message.getMessageProperties().getReceivedDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);

        JsonNode event = objectMapper.readTree(new String(message.getBody(), StandardCharsets.UTF_8));
        assertThat(UUID.fromString(event.get("eventId").asText())).isNotNull();
        assertThat(event.get("schemaVersion").asInt()).isEqualTo(1);
        assertThat(event.get("userId").asLong()).isEqualTo(fixture.userId());
        assertThat(event.get("orderId").asLong()).isEqualTo(fixture.orderId());
        assertThat(event.get("sessionId").asLong()).isEqualTo(fixture.sessionId());
        assertThat(event.get("ticketNo").asText()).isEqualTo(ticketNo);
        assertThat(Instant.parse(event.get("occurredAt").asText())).isNotNull();
        assertThat(rabbitTemplate.receive(NotificationRabbitTopology.TICKET_ISSUED_QUEUE, 100)).isNull();

        mockMvc.perform(post("/api/orders/{orderId}/pay", fixture.orderId())
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(fixture.userId(), fixture.username())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_STATE_CONFLICT"));
        assertThat(rabbitTemplate.receive(NotificationRabbitTopology.TICKET_ISSUED_QUEUE, 100)).isNull();
    }

    @AfterEach
    void cleanupAndVerifyNoSyntheticResidue() {
        assertThat(rabbitTemplate.receive(NotificationRabbitTopology.TICKET_ISSUED_QUEUE, 100)).isNull();
        if (orderId != null) {
            jdbcTemplate.update("DELETE FROM ticket WHERE order_id = ?", orderId);
            jdbcTemplate.update("DELETE FROM ticket_order WHERE id = ?", orderId);
        }
        if (sessionId != null) {
            jdbcTemplate.update("DELETE FROM event_inventory WHERE session_id = ?", sessionId);
            jdbcTemplate.update("DELETE FROM event_sessions WHERE id = ?", sessionId);
        }
        if (eventId != null) {
            jdbcTemplate.update("DELETE FROM events WHERE id = ?", eventId);
        }
        if (userId != null) {
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
        }

        assertThat(count("SELECT COUNT(*) FROM users WHERE username LIKE ?", PREFIX + "%")).isZero();
        assertThat(count("SELECT COUNT(*) FROM events WHERE title LIKE ?", PREFIX + "%")).isZero();
        assertThat(count("SELECT COUNT(*) FROM event_sessions WHERE name LIKE ?", PREFIX + "%")).isZero();
    }

    private Fixture createFixture() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        String username = PREFIX + suffix;
        jdbcTemplate.update("INSERT INTO users (username, password_hash, display_name, role) "
                        + "VALUES (?, ?, ?, 'USER')",
                username, ENCODED_PASSWORD, "Payment notification fixture");
        userId = requiredLong("SELECT id FROM users WHERE username = ?", username);

        String title = PREFIX + suffix;
        jdbcTemplate.update("INSERT INTO events (organizer_user_id, title, status) VALUES (?, ?, 'PUBLISHED')",
                userId, title);
        eventId = requiredLong("SELECT id FROM events WHERE title = ?", title);

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        String sessionName = PREFIX + suffix;
        jdbcTemplate.update("INSERT INTO event_sessions "
                        + "(event_id, name, sale_mode, sale_start_at, sale_end_at, start_at, end_at) "
                        + "VALUES (?, ?, 'NORMAL', ?, ?, ?, ?)",
                eventId, sessionName, now.minusHours(1), now.plusHours(1), now.plusHours(2), now.plusHours(3));
        sessionId = requiredLong("SELECT id FROM event_sessions WHERE name = ?", sessionName);
        jdbcTemplate.update("INSERT INTO event_inventory "
                        + "(session_id, total_stock, available_stock, locked_stock, sold_stock) "
                        + "VALUES (?, 1, 0, 1, 0)",
                sessionId);

        String orderNo = "P11B3-" + UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
        jdbcTemplate.update("INSERT INTO ticket_order "
                        + "(order_no, user_id, session_id, status, expire_at) VALUES (?, ?, ?, 'WAIT_PAY', ?)",
                orderNo, userId, sessionId, now.plusMinutes(5));
        orderId = requiredLong("SELECT id FROM ticket_order WHERE order_no = ?", orderNo);
        return new Fixture(userId, username, sessionId, orderId);
    }

    private String bearerToken(long currentUserId, String username) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(AccessTokenContract.ISSUER)
                .subject(Long.toString(currentUserId))
                .issuedAt(now.minusSeconds(1))
                .expiresAt(now.plus(AccessTokenContract.TIME_TO_LIVE))
                .claim(AccessTokenContract.USERNAME_CLAIM, username)
                .claim(AccessTokenContract.ROLE_CLAIM, UserRole.USER.name())
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).type("JWT").build();
        return "Bearer " + jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    private String orderStatus(long currentOrderId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM ticket_order WHERE id = ?", String.class, currentOrderId);
    }

    private InventoryState inventory(long currentSessionId) {
        return jdbcTemplate.queryForObject(
                "SELECT total_stock, available_stock, locked_stock, sold_stock "
                        + "FROM event_inventory WHERE session_id = ?",
                (resultSet, rowNum) -> new InventoryState(
                        resultSet.getInt("total_stock"),
                        resultSet.getInt("available_stock"),
                        resultSet.getInt("locked_stock"),
                        resultSet.getInt("sold_stock")),
                currentSessionId);
    }

    private TicketState ticketState(long currentOrderId) {
        return jdbcTemplate.queryForObject(
                "SELECT ticket_no, user_id, session_id, status FROM ticket WHERE order_id = ?",
                (resultSet, rowNum) -> new TicketState(
                        resultSet.getString("ticket_no"),
                        resultSet.getLong("user_id"),
                        resultSet.getLong("session_id"),
                        resultSet.getString("status")),
                currentOrderId);
    }

    private long requiredLong(String sql, Object argument) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, argument);
        if (value == null) {
            throw new IllegalStateException("Expected fixture identifier");
        }
        return value;
    }

    private long count(String sql, Object argument) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, argument);
        return value == null ? 0 : value;
    }

    private record Fixture(long userId, String username, long sessionId, long orderId) {
    }

    private record InventoryState(int total, int available, int locked, int sold) {
    }

    private record TicketState(String ticketNo, long userId, long sessionId, String status) {
    }
}

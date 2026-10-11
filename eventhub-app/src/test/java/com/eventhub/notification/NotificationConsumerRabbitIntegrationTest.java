package com.eventhub.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

import com.eventhub.notification.api.event.TicketIssuedEvent;
import com.eventhub.notification.rabbit.NotificationRabbitTopology;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = "spring.rabbitmq.listener.simple.auto-startup=true")
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class NotificationConsumerRabbitIntegrationTest {

    private static final Duration ASYNC_TIMEOUT = Duration.ofSeconds(10);
    private static final long USER_ID = 91_501L;
    private static final Instant OCCURRED_AT = Instant.parse("2026-10-11T13:00:00.123456Z");

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private RabbitAdmin rabbitAdmin;

    @Autowired
    private RabbitListenerEndpointRegistry listenerRegistry;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private final Set<String> eventIds = new HashSet<>();
    private final Set<String> ticketNumbers = new HashSet<>();

    @BeforeEach
    void prepareEmptyQueueAndVerifyListenerIsRunning() {
        rabbitAdmin.purgeQueue(NotificationRabbitTopology.TICKET_ISSUED_QUEUE, false);
        await("notification listener running", () -> listenerRegistry.getListenerContainers().stream()
                .anyMatch(container -> container.isRunning()));
        assertThat(readyMessageCount()).isZero();
    }

    @Test
    void consumesAndAcknowledgesValidAndEquivalentDuplicateDeliveries() {
        TicketIssuedEvent first = event(UUID.randomUUID(), USER_ID, ticketNo(), OCCURRED_AT);
        publish(first);
        await("first notification committed", () -> countByEvent(first.eventId().toString()) == 1);
        awaitQueueEmpty();

        publish(first);
        awaitQueueEmpty();
        assertThat(countByTicket(first.ticketNo())).isEqualTo(1);

        TicketIssuedEvent sameBusiness = event(
                UUID.randomUUID(), USER_ID, first.ticketNo(), OCCURRED_AT.plusSeconds(1));
        publish(sameBusiness);

        TicketIssuedEvent sentinel = event(
                UUID.randomUUID(), USER_ID, ticketNo(), OCCURRED_AT.plusSeconds(2));
        publish(sentinel);
        await("duplicate deliveries completed", () -> countByEvent(sentinel.eventId().toString()) == 1);
        awaitQueueEmpty();

        assertThat(countByTicket(first.ticketNo())).isEqualTo(1);
        assertThat(countByEvent(first.eventId().toString())).isEqualTo(1);
        assertThat(countByEvent(sameBusiness.eventId().toString())).isZero();
    }

    @Test
    void rejectsConflictingAndPoisonMessagesWithoutRequeueAndRemainsOperational() throws Exception {
        TicketIssuedEvent persisted = event(UUID.randomUUID(), USER_ID, ticketNo(), OCCURRED_AT);
        publish(persisted);
        await("conflict fixture committed", () -> countByEvent(persisted.eventId().toString()) == 1);
        awaitQueueEmpty();

        TicketIssuedEvent conflict = event(
                persisted.eventId(), USER_ID + 1, persisted.ticketNo(), OCCURRED_AT);
        publish(conflict);

        TicketIssuedEvent occurredAtConflict = event(
                persisted.eventId(), USER_ID, persisted.ticketNo(), OCCURRED_AT.plusSeconds(1));
        publish(occurredAtConflict);

        sendRawJson("{not-valid-json");
        awaitQueueEmpty();

        TicketIssuedEvent unsupportedFixture = event(UUID.randomUUID(), USER_ID, ticketNo(), OCCURRED_AT);
        ObjectNode unsupported = objectMapper.valueToTree(unsupportedFixture);
        unsupported.put("schemaVersion", 2);
        sendRawJson(objectMapper.writeValueAsString(unsupported));
        awaitQueueEmpty();
        assertThat(countByEvent(unsupportedFixture.eventId().toString())).isZero();

        TicketIssuedEvent databaseFailure = event(
                UUID.randomUUID(), USER_ID, "TKT-" + "A".repeat(61), OCCURRED_AT);
        publish(databaseFailure);
        awaitQueueEmpty();
        assertThat(countByEvent(databaseFailure.eventId().toString())).isZero();

        TicketIssuedEvent finalValid = event(UUID.randomUUID(), USER_ID, ticketNo(), OCCURRED_AT.plusSeconds(2));
        publish(finalValid);
        await("consumer remains operational", () -> countByEvent(finalValid.eventId().toString()) == 1);
        awaitQueueEmpty();
        assertThat(countByTicket(persisted.ticketNo())).isEqualTo(1);
        assertThat(userIdByEvent(persisted.eventId().toString())).isEqualTo(USER_ID);
    }

    @AfterEach
    void cleanupAndVerifyNoRabbitOrDatabaseResidue() {
        rabbitAdmin.purgeQueue(NotificationRabbitTopology.TICKET_ISSUED_QUEUE, false);
        eventIds.forEach(eventId -> jdbcTemplate.update(
                "DELETE FROM user_notification WHERE event_id = ?", eventId));
        ticketNumbers.forEach(ticketNo -> jdbcTemplate.update(
                "DELETE FROM user_notification WHERE business_reference = ?", ticketNo));

        eventIds.forEach(eventId -> assertThat(countByEvent(eventId)).isZero());
        ticketNumbers.forEach(ticketNo -> assertThat(countByTicket(ticketNo)).isZero());
        assertThat(readyMessageCount()).isZero();
    }

    private void publish(TicketIssuedEvent event) {
        rabbitTemplate.convertAndSend(
                NotificationRabbitTopology.EXCHANGE,
                NotificationRabbitTopology.TICKET_ISSUED_ROUTING_KEY,
                event);
    }

    private void sendRawJson(String json) {
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setContentEncoding(StandardCharsets.UTF_8.name());
        rabbitTemplate.send(
                NotificationRabbitTopology.EXCHANGE,
                NotificationRabbitTopology.TICKET_ISSUED_ROUTING_KEY,
                new Message(json.getBytes(StandardCharsets.UTF_8), properties));
    }

    private TicketIssuedEvent event(UUID eventId, long userId, String ticketNo, Instant occurredAt) {
        eventIds.add(eventId.toString());
        ticketNumbers.add(ticketNo);
        return new TicketIssuedEvent(eventId, 1, userId, 92_501L, 93_501L, ticketNo, occurredAt);
    }

    private static String ticketNo() {
        return "TKT-" + UUID.randomUUID().toString().replace("-", "").toUpperCase();
    }

    private void awaitQueueEmpty() {
        await("notification queue drained without requeue", () -> readyMessageCount() == 0);
    }

    private int readyMessageCount() {
        Long count = rabbitTemplate.execute(channel ->
                channel.messageCount(NotificationRabbitTopology.TICKET_ISSUED_QUEUE));
        return count == null ? -1 : Math.toIntExact(count);
    }

    private long userIdByEvent(String eventId) {
        Long userId = jdbcTemplate.queryForObject(
                "SELECT user_id FROM user_notification WHERE event_id = ?", Long.class, eventId);
        if (userId == null) {
            throw new IllegalStateException("Expected notification user");
        }
        return userId;
    }

    private int countByEvent(String eventId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_notification WHERE event_id = ?", Integer.class, eventId);
        return count == null ? 0 : count;
    }

    private int countByTicket(String ticketNo) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_notification WHERE business_reference = ?",
                Integer.class,
                ticketNo);
        return count == null ? 0 : count;
    }

    private static void await(String description, BooleanSupplier condition) {
        long deadline = System.nanoTime() + ASYNC_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            LockSupport.parkNanos(Duration.ofMillis(25).toNanos());
        }
        throw new AssertionError("Timed out waiting for " + description);
    }
}

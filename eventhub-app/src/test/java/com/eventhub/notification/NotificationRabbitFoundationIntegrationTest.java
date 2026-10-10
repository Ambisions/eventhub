package com.eventhub.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import com.eventhub.notification.api.NotificationEventPublisherApi;
import com.eventhub.notification.api.event.TicketIssuedEvent;
import com.eventhub.notification.rabbit.NotificationRabbitTopology;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class NotificationRabbitFoundationIntegrationTest {

    @Autowired
    private NotificationEventPublisherApi publisher;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private MessageConverter messageConverter;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void jsonConverterPreservesTheFrozenContractAndInstantRoundTrip() throws Exception {
        TicketIssuedEvent event = event();
        Message message = messageConverter.toMessage(event, new MessageProperties());

        assertThat(message.getMessageProperties().getContentType()).isEqualTo(MessageProperties.CONTENT_TYPE_JSON);
        JsonNode json = objectMapper.readTree(message.getBody());
        assertJsonContract(json, event);

        Object roundTrip = messageConverter.fromMessage(message);
        assertThat(roundTrip).isEqualTo(event);
    }

    @Test
    void publishesPersistentJsonToTheFrozenRabbitRoute() throws Exception {
        assertThat(rabbitTemplate.receive(NotificationRabbitTopology.TICKET_ISSUED_QUEUE, 100)).isNull();
        TicketIssuedEvent event = event();

        publisher.publishTicketIssued(event);

        Message message = rabbitTemplate.receive(NotificationRabbitTopology.TICKET_ISSUED_QUEUE, 5_000);
        assertThat(message).isNotNull();
        assertThat(message.getMessageProperties().getReceivedExchange())
                .isEqualTo(NotificationRabbitTopology.EXCHANGE);
        assertThat(message.getMessageProperties().getReceivedRoutingKey())
                .isEqualTo(NotificationRabbitTopology.TICKET_ISSUED_ROUTING_KEY);
        assertThat(message.getMessageProperties().getContentType()).isEqualTo(MessageProperties.CONTENT_TYPE_JSON);
        assertThat(message.getMessageProperties().getReceivedDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);

        JsonNode json = objectMapper.readTree(new String(message.getBody(), StandardCharsets.UTF_8));
        assertJsonContract(json, event);
        assertThat(Instant.parse(json.get("occurredAt").asText())).isEqualTo(event.occurredAt());
        assertThat(rabbitTemplate.receive(NotificationRabbitTopology.TICKET_ISSUED_QUEUE, 100)).isNull();
    }

    @AfterEach
    void verifyNoFixtureMessageResidue() {
        assertThat(rabbitTemplate.receive(NotificationRabbitTopology.TICKET_ISSUED_QUEUE, 100)).isNull();
    }

    private static TicketIssuedEvent event() {
        return new TicketIssuedEvent(
                UUID.randomUUID(),
                1,
                91_001L,
                92_001L,
                93_001L,
                "TKT-" + UUID.randomUUID().toString().replace("-", "").toUpperCase(),
                Instant.parse("2026-10-10T12:34:56.123456Z"));
    }

    private static void assertJsonContract(JsonNode json, TicketIssuedEvent event) {
        assertThat(json.get("eventId").asText()).isEqualTo(event.eventId().toString());
        assertThat(json.get("schemaVersion").asInt()).isEqualTo(1);
        assertThat(json.get("userId").asLong()).isEqualTo(event.userId());
        assertThat(json.get("orderId").asLong()).isEqualTo(event.orderId());
        assertThat(json.get("sessionId").asLong()).isEqualTo(event.sessionId());
        assertThat(json.get("ticketNo").asText()).isEqualTo(event.ticketNo());
        assertThat(Instant.parse(json.get("occurredAt").asText())).isEqualTo(event.occurredAt());
    }
}

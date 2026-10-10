package com.eventhub.notification.rabbit;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.time.Instant;
import java.util.UUID;

import com.eventhub.notification.api.error.NotificationPublishException;
import com.eventhub.notification.api.event.TicketIssuedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

@ExtendWith(MockitoExtension.class)
class RabbitNotificationEventPublisherTest {

    @Mock
    private RabbitTemplate rabbitTemplate;

    @Test
    void publishesExactlyOnceToFrozenRoute() {
        TicketIssuedEvent event = event();
        RabbitNotificationEventPublisher publisher = new RabbitNotificationEventPublisher(rabbitTemplate);

        publisher.publishTicketIssued(event);

        verify(rabbitTemplate).convertAndSend(
                "eventhub.notification.v1",
                "ticket.issued.v1",
                event);
        verifyNoMoreInteractions(rabbitTemplate);
    }

    @Test
    void translatesAmqpFailureAndPreservesCause() {
        TicketIssuedEvent event = event();
        AmqpException cause = new AmqpException("broker unavailable");
        doThrow(cause).when(rabbitTemplate).convertAndSend(
                "eventhub.notification.v1",
                "ticket.issued.v1",
                event);
        RabbitNotificationEventPublisher publisher = new RabbitNotificationEventPublisher(rabbitTemplate);

        NotificationPublishException exception = assertThrows(
                NotificationPublishException.class,
                () -> publisher.publishTicketIssued(event));

        assertSame(cause, exception.getCause());
    }

    private static TicketIssuedEvent event() {
        return new TicketIssuedEvent(
                UUID.fromString("123e4567-e89b-12d3-a456-426614174000"),
                1,
                11,
                22,
                33,
                "TKT-123",
                Instant.parse("2026-10-10T10:15:30Z"));
    }
}

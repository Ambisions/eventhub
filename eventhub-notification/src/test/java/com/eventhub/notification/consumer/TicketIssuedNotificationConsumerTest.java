package com.eventhub.notification.consumer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.UUID;

import com.eventhub.notification.api.event.TicketIssuedEvent;
import com.eventhub.notification.service.UserNotificationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

@ExtendWith(MockitoExtension.class)
class TicketIssuedNotificationConsumerTest {

    @Mock
    private UserNotificationService service;

    @Test
    void returnsNormallyAfterSuccessfulPersistence() {
        TicketIssuedEvent event = event();
        TicketIssuedNotificationConsumer consumer = new TicketIssuedNotificationConsumer(service);

        assertDoesNotThrow(() -> consumer.consume(event));

        verify(service).recordTicketIssued(event);
        verify(service, never()).verifyEquivalentDuplicate(any(), any());
    }

    @Test
    void verifiesADuplicateOnlyAfterTheTransactionalCallThrows() {
        TicketIssuedEvent event = event();
        DuplicateKeyException duplicate = new DuplicateKeyException("duplicate");
        doThrow(duplicate).when(service).recordTicketIssued(event);
        doNothing().when(service).verifyEquivalentDuplicate(event, duplicate);
        TicketIssuedNotificationConsumer consumer = new TicketIssuedNotificationConsumer(service);

        assertDoesNotThrow(() -> consumer.consume(event));

        verify(service).verifyEquivalentDuplicate(event, duplicate);
    }

    @Test
    void propagatesAConflictingDuplicate() {
        TicketIssuedEvent event = event();
        DuplicateKeyException duplicate = new DuplicateKeyException("duplicate");
        IllegalStateException conflict = new IllegalStateException("conflict");
        doThrow(duplicate).when(service).recordTicketIssued(event);
        doThrow(conflict).when(service).verifyEquivalentDuplicate(event, duplicate);
        TicketIssuedNotificationConsumer consumer = new TicketIssuedNotificationConsumer(service);

        IllegalStateException actual = assertThrows(IllegalStateException.class,
                () -> consumer.consume(event));

        assertSame(conflict, actual);
    }

    @Test
    void propagatesUnknownRuntimeFailureWithoutDuplicateVerification() {
        TicketIssuedEvent event = event();
        IllegalStateException failure = new IllegalStateException("failure");
        doThrow(failure).when(service).recordTicketIssued(event);
        TicketIssuedNotificationConsumer consumer = new TicketIssuedNotificationConsumer(service);

        IllegalStateException actual = assertThrows(IllegalStateException.class,
                () -> consumer.consume(event));

        assertSame(failure, actual);
        verify(service, never()).verifyEquivalentDuplicate(any(), any());
    }

    private static TicketIssuedEvent event() {
        return new TicketIssuedEvent(
                UUID.randomUUID(),
                1,
                91_001L,
                92_001L,
                93_001L,
                "TKT-1234567890ABCDEF1234567890ABCDEF",
                Instant.parse("2026-10-11T12:34:56.123456Z"));
    }
}

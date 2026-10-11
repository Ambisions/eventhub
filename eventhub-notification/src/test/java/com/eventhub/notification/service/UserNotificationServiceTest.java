package com.eventhub.notification.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.SQLIntegrityConstraintViolationException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

import com.eventhub.notification.api.event.TicketIssuedEvent;
import com.eventhub.notification.mapper.UserNotificationMapper;
import com.eventhub.notification.repository.UserNotificationEntity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

@ExtendWith(MockitoExtension.class)
class UserNotificationServiceTest {

    private static final UUID EVENT_ID = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
    private static final long USER_ID = 91_001L;
    private static final String TICKET_NO = "TKT-1234567890ABCDEF1234567890ABCDEF";
    private static final Instant OCCURRED_AT = Instant.parse("2026-10-11T12:34:56.123456789Z");

    @Mock
    private UserNotificationMapper mapper;

    @Test
    void mapsTicketIssuedEventToTheFrozenPersistenceProjection() {
        when(mapper.insert(any(UserNotificationEntity.class))).thenAnswer(invocation -> {
            UserNotificationEntity entity = invocation.getArgument(0);
            entity.setId(1L);
            return 1;
        });
        UserNotificationService service = new UserNotificationService(mapper);

        service.recordTicketIssued(event(EVENT_ID, USER_ID, TICKET_NO, OCCURRED_AT));

        ArgumentCaptor<UserNotificationEntity> captor = ArgumentCaptor.forClass(UserNotificationEntity.class);
        verify(mapper).insert(captor.capture());
        UserNotificationEntity entity = captor.getValue();
        assertEquals(EVENT_ID.toString(), entity.getEventId());
        assertEquals(USER_ID, entity.getUserId());
        assertEquals("TICKET_ISSUED", entity.getType());
        assertEquals(TICKET_NO, entity.getBusinessReference());
        assertEquals(LocalDateTime.parse("2026-10-11T12:34:56.123456"), entity.getOccurredAt());
        assertNull(entity.getCreatedAt());
    }

    @Test
    void rejectsAnInsertThatDoesNotCreateExactlyOneGeneratedRow() {
        when(mapper.insert(any(UserNotificationEntity.class))).thenReturn(1);
        UserNotificationService service = new UserNotificationService(mapper);

        assertThrows(IllegalStateException.class,
                () -> service.recordTicketIssued(event(EVENT_ID, USER_ID, TICKET_NO, OCCURRED_AT)));
    }

    @Test
    void acceptsAnEquivalentEventIdentityDuplicate() {
        UserNotificationEntity existing = persisted(1L, EVENT_ID, USER_ID, TICKET_NO, OCCURRED_AT);
        when(mapper.selectOne(any())).thenReturn(existing, existing);
        UserNotificationService service = new UserNotificationService(mapper);

        assertDoesNotThrow(() -> service.verifyEquivalentDuplicate(
                event(EVENT_ID, USER_ID, TICKET_NO, OCCURRED_AT),
                duplicate("uk_user_notification_event")));
    }

    @Test
    void acceptsAnEquivalentBusinessIdentityWithADifferentEventIdentity() {
        UserNotificationEntity existing = persisted(
                1L, UUID.randomUUID(), USER_ID, TICKET_NO, OCCURRED_AT.minusSeconds(5));
        when(mapper.selectOne(any())).thenReturn(null, existing);
        UserNotificationService service = new UserNotificationService(mapper);

        assertDoesNotThrow(() -> service.verifyEquivalentDuplicate(
                event(EVENT_ID, USER_ID, TICKET_NO, OCCURRED_AT),
                duplicate("uk_user_notification_business")));
    }

    @Test
    void rejectsAConflictingEventIdentityDuplicate() {
        UserNotificationEntity existing = persisted(1L, EVENT_ID, USER_ID + 1, TICKET_NO, OCCURRED_AT);
        when(mapper.selectOne(any())).thenReturn(existing, existing);
        UserNotificationService service = new UserNotificationService(mapper);

        assertThrows(IllegalStateException.class, () -> service.verifyEquivalentDuplicate(
                event(EVENT_ID, USER_ID, TICKET_NO, OCCURRED_AT),
                duplicate("uk_user_notification_event")));
    }

    @Test
    void rejectsAConflictingBusinessIdentityDuplicate() {
        UserNotificationEntity existing = persisted(
                1L, UUID.randomUUID(), USER_ID + 1, TICKET_NO, OCCURRED_AT);
        when(mapper.selectOne(any())).thenReturn(null, existing);
        UserNotificationService service = new UserNotificationService(mapper);

        assertThrows(IllegalStateException.class, () -> service.verifyEquivalentDuplicate(
                event(EVENT_ID, USER_ID, TICKET_NO, OCCURRED_AT),
                duplicate("uk_user_notification_business")));
    }

    @Test
    void rejectsSameEventWithDifferentOccurredAtEvenWhenBusinessConstraintIsReported() {
        UserNotificationEntity existing = persisted(1L, EVENT_ID, USER_ID, TICKET_NO, OCCURRED_AT);
        when(mapper.selectOne(any())).thenReturn(existing, existing);
        UserNotificationService service = new UserNotificationService(mapper);

        assertThrows(IllegalStateException.class, () -> service.verifyEquivalentDuplicate(
                event(EVENT_ID, USER_ID, TICKET_NO, OCCURRED_AT.plusSeconds(1)),
                duplicate("uk_user_notification_business")));
    }

    @Test
    void rejectsCrossIdentityConflictBetweenDifferentRows() {
        UserNotificationEntity eventRow = persisted(1L, EVENT_ID, USER_ID, TICKET_NO, OCCURRED_AT);
        UserNotificationEntity businessRow = persisted(2L, UUID.randomUUID(), USER_ID, TICKET_NO, OCCURRED_AT);
        when(mapper.selectOne(any())).thenReturn(eventRow, businessRow);
        UserNotificationService service = new UserNotificationService(mapper);

        assertThrows(IllegalStateException.class, () -> service.verifyEquivalentDuplicate(
                event(EVENT_ID, USER_ID, TICKET_NO, OCCURRED_AT),
                duplicate("uk_user_notification_event")));
    }

    @Test
    void rejectsKnownConstraintWhenTheExpectedFactIsMissing() {
        when(mapper.selectOne(any())).thenReturn(null).thenReturn(null);
        UserNotificationService service = new UserNotificationService(mapper);

        assertThrows(IllegalStateException.class, () -> service.verifyEquivalentDuplicate(
                event(EVENT_ID, USER_ID, TICKET_NO, OCCURRED_AT),
                duplicate("uk_user_notification_event")));
    }

    @Test
    void propagatesAnUnknownDuplicateConstraintWithoutQuerying() {
        DuplicateKeyException duplicate = duplicate("some_other_constraint");
        UserNotificationService service = new UserNotificationService(mapper);

        DuplicateKeyException actual = assertThrows(DuplicateKeyException.class,
                () -> service.verifyEquivalentDuplicate(
                        event(EVENT_ID, USER_ID, TICKET_NO, OCCURRED_AT), duplicate));

        assertSame(duplicate, actual);
        verify(mapper, never()).selectOne(any());
    }

    private static TicketIssuedEvent event(UUID eventId, long userId, String ticketNo, Instant occurredAt) {
        return new TicketIssuedEvent(eventId, 1, userId, 92_001L, 93_001L, ticketNo, occurredAt);
    }

    private static UserNotificationEntity persisted(
            long id,
            UUID eventId,
            long userId,
            String ticketNo,
            Instant occurredAt) {
        UserNotificationEntity entity = new UserNotificationEntity();
        entity.setId(id);
        entity.setEventId(eventId.toString());
        entity.setUserId(userId);
        entity.setType("TICKET_ISSUED");
        entity.setBusinessReference(ticketNo);
        entity.setOccurredAt(LocalDateTime.ofInstant(occurredAt, java.time.ZoneOffset.UTC)
                .withNano((occurredAt.getNano() / 1_000) * 1_000));
        return entity;
    }

    private static DuplicateKeyException duplicate(String constraint) {
        return new DuplicateKeyException(
                "notification insert failed",
                new SQLIntegrityConstraintViolationException(
                        "Duplicate entry for key 'user_notification." + constraint + "'",
                        "23000",
                        1062));
    }
}

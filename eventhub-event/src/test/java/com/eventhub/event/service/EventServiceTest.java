package com.eventhub.event.service;

import java.time.LocalDateTime;
import java.util.List;

import com.eventhub.event.api.command.UpdateEventCommand;
import com.eventhub.event.api.error.EventErrorCode;
import com.eventhub.event.api.error.EventException;
import com.eventhub.event.api.error.EventCheckInAuthorizationException;
import com.eventhub.event.api.error.EventCheckInAuthorizationFailure;
import com.eventhub.event.api.model.EventStatus;
import com.eventhub.event.api.model.EditableEventSessionContext;
import com.eventhub.event.api.model.ManagedEventView;
import com.eventhub.event.mapper.EventMapper;
import com.eventhub.event.mapper.EventSessionMapper;
import com.eventhub.event.repository.EventEntity;
import com.eventhub.event.repository.EventSessionEntity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EventServiceTest {

    private static final long OWNER_ID = 10L;
    private static final long EVENT_ID = 20L;

    @Mock
    private EventMapper eventMapper;

    @Mock
    private EventSessionMapper eventSessionMapper;

    @InjectMocks
    private EventService eventService;

    @Test
    void treatsZeroAffectedRowsAsValidNoOpOnlyAfterOwnedEditableDiagnosis() {
        EventEntity event = event(OWNER_ID, EventStatus.DRAFT, "Title", "Description");
        when(eventMapper.updateEditableEvent(OWNER_ID, EVENT_ID, "Title", "Description")).thenReturn(0);
        when(eventMapper.selectById(EVENT_ID)).thenReturn(event);

        ManagedEventView result = eventService.updateEvent(
                OWNER_ID,
                EVENT_ID,
                new UpdateEventCommand("Title", "Description"));

        assertEquals(EVENT_ID, result.id());
        assertEquals(EventStatus.DRAFT, result.status());
    }

    @Test
    void masksNonOwnerWhenZeroAffectedRowsRequiresDiagnosis() {
        when(eventMapper.updateEditableEvent(OWNER_ID, EVENT_ID, "Title", null)).thenReturn(0);
        when(eventMapper.selectById(EVENT_ID)).thenReturn(event(999L, EventStatus.DRAFT, "Title", null));

        EventException exception = assertThrows(
                EventException.class,
                () -> eventService.updateEvent(
                        OWNER_ID, EVENT_ID, new UpdateEventCommand("Title", null)));

        assertEquals(EventErrorCode.EVENT_NOT_FOUND, exception.getErrorCode());
    }

    @Test
    void calculatesMaximumIntPageOffsetUsingLongArithmetic() {
        long expectedOffset = 214_748_364_700L;
        when(eventMapper.selectPublishedPage(expectedOffset, 101)).thenReturn(List.of());

        var result = eventService.listPublishedEvents(Integer.MAX_VALUE, 100);

        assertEquals(Integer.MAX_VALUE, result.page());
        assertEquals(100, result.size());
        assertEquals(List.of(), result.items());
        verify(eventMapper).selectPublishedPage(expectedOffset, 101);
    }

    @Test
    void returnsMinimalContextAfterLockingEditableOwnedParentEvent() {
        EventSessionEntity session = new EventSessionEntity();
        session.setId(30L);
        session.setEventId(EVENT_ID);
        when(eventSessionMapper.selectById(30L)).thenReturn(session);
        when(eventMapper.selectByIdForUpdate(EVENT_ID))
                .thenReturn(event(OWNER_ID, EventStatus.DRAFT, "Title", null));

        EditableEventSessionContext context = eventService.requireEditableOwnedSession(OWNER_ID, 30L);

        assertEquals(30L, context.sessionId());
        assertEquals(EVENT_ID, context.eventId());
        verify(eventMapper).selectByIdForUpdate(EVENT_ID);
    }

    @Test
    void hidesNonOwnerAndRejectsNonEditableParentThroughSessionGuard() {
        EventSessionEntity session = new EventSessionEntity();
        session.setId(30L);
        session.setEventId(EVENT_ID);
        when(eventSessionMapper.selectById(30L)).thenReturn(session);
        when(eventMapper.selectByIdForUpdate(EVENT_ID))
                .thenReturn(event(999L, EventStatus.DRAFT, "Title", null));

        EventException nonOwner = assertThrows(EventException.class,
                () -> eventService.requireEditableOwnedSession(OWNER_ID, 30L));
        assertEquals(EventErrorCode.EVENT_NOT_FOUND, nonOwner.getErrorCode());

        when(eventMapper.selectByIdForUpdate(EVENT_ID))
                .thenReturn(event(OWNER_ID, EventStatus.PENDING_REVIEW, "Title", null));
        EventException nonEditable = assertThrows(EventException.class,
                () -> eventService.requireEditableOwnedSession(OWNER_ID, 30L));
        assertEquals(EventErrorCode.EVENT_NOT_EDITABLE, nonEditable.getErrorCode());
    }

    @Test
    void authorizesOwnedSessionUsingSingleOwnershipLookupAndTimePolicy() {
        LocalDateTime start = LocalDateTime.of(2030, 6, 1, 10, 0);
        EventSessionEntity session = new EventSessionEntity();
        session.setId(30L);
        session.setEventId(EVENT_ID);
        session.setStartAt(start);
        session.setEndAt(start.plusHours(2));
        when(eventSessionMapper.selectOwnedCheckInSession(OWNER_ID, 30L)).thenReturn(session);

        eventService.requireCheckInEligibility(OWNER_ID, 30L, start);

        verify(eventSessionMapper).selectOwnedCheckInSession(OWNER_ID, 30L);
        verifyNoInteractions(eventMapper);
    }

    @Test
    void hidesMissingSessionAndNonOwnershipBehindOneFailure() {
        LocalDateTime checkInTime = LocalDateTime.of(2030, 6, 1, 10, 0);
        when(eventSessionMapper.selectOwnedCheckInSession(OWNER_ID, 30L)).thenReturn(null);

        EventCheckInAuthorizationException exception = assertThrows(
                EventCheckInAuthorizationException.class,
                () -> eventService.requireCheckInEligibility(OWNER_ID, 30L, checkInTime));

        assertEquals(EventCheckInAuthorizationFailure.NOT_FOUND_OR_NOT_OWNER, exception.getFailure());
        verify(eventSessionMapper).selectOwnedCheckInSession(OWNER_ID, 30L);
    }

    private static EventEntity event(long ownerId, EventStatus status, String title, String description) {
        EventEntity event = new EventEntity();
        event.setId(EVENT_ID);
        event.setOrganizerUserId(ownerId);
        event.setStatus(status);
        event.setTitle(title);
        event.setDescription(description);
        return event;
    }
}

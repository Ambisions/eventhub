package com.eventhub.event.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.eventhub.event.api.command.CreateEventSessionCommand;
import com.eventhub.event.api.command.UpdateEventCommand;
import com.eventhub.event.api.command.UpdateEventSessionCommand;
import com.eventhub.event.api.error.EventErrorCode;
import com.eventhub.event.api.error.EventException;
import com.eventhub.event.api.error.EventCheckInAuthorizationException;
import com.eventhub.event.api.error.EventCheckInAuthorizationFailure;
import com.eventhub.event.api.model.EventStatus;
import com.eventhub.event.api.model.EditableEventSessionContext;
import com.eventhub.event.api.model.ManagedEventView;
import com.eventhub.event.api.model.SessionSaleMode;
import com.eventhub.event.mapper.EventMapper;
import com.eventhub.event.mapper.EventSessionMapper;
import com.eventhub.event.repository.EventEntity;
import com.eventhub.event.repository.EventSessionEntity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.ArgumentMatchers.any;
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

    @Test
    void defaultsNullCreateModeToNormalAndPropagatesExplicitModes() {
        assertCreateMode(null, SessionSaleMode.NORMAL);
        assertCreateMode(SessionSaleMode.NORMAL, SessionSaleMode.NORMAL);
        assertCreateMode(SessionSaleMode.FLASH, SessionSaleMode.FLASH);
    }

    @Test
    void passesNullUpdateModeThroughSoDynamicSqlRetainsThePersistedMode() {
        SessionTimes times = sessionTimes();
        EventSessionEntity existing = session(SessionSaleMode.FLASH, times);
        EventSessionEntity persisted = session(SessionSaleMode.FLASH, times);
        when(eventSessionMapper.selectById(30L)).thenReturn(existing, persisted);
        when(eventMapper.selectByIdForUpdate(EVENT_ID))
                .thenReturn(event(OWNER_ID, EventStatus.DRAFT, "Title", null));
        when(eventSessionMapper.updateSession(
                30L, EVENT_ID, "Session", "Hall", null,
                times.saleStart(), times.saleEnd(), times.start(), times.end()))
                .thenReturn(1);

        var result = eventService.updateSession(OWNER_ID, 30L, new UpdateEventSessionCommand(
                "Session", "Hall", null,
                times.saleStart(), times.saleEnd(), times.start(), times.end()));

        assertEquals(SessionSaleMode.FLASH, result.saleMode());
        verify(eventSessionMapper).updateSession(
                30L, EVENT_ID, "Session", "Hall", null,
                times.saleStart(), times.saleEnd(), times.start(), times.end());
    }

    @Test
    void propagatesExplicitUpdateMode() {
        SessionTimes times = sessionTimes();
        EventSessionEntity existing = session(SessionSaleMode.NORMAL, times);
        EventSessionEntity persisted = session(SessionSaleMode.FLASH, times);
        when(eventSessionMapper.selectById(30L)).thenReturn(existing, persisted);
        when(eventMapper.selectByIdForUpdate(EVENT_ID))
                .thenReturn(event(OWNER_ID, EventStatus.REJECTED, "Title", null));
        when(eventSessionMapper.updateSession(
                30L, EVENT_ID, "Session", "Hall", SessionSaleMode.FLASH,
                times.saleStart(), times.saleEnd(), times.start(), times.end()))
                .thenReturn(1);

        var result = eventService.updateSession(OWNER_ID, 30L, new UpdateEventSessionCommand(
                "Session", "Hall", SessionSaleMode.FLASH,
                times.saleStart(), times.saleEnd(), times.start(), times.end()));

        assertEquals(SessionSaleMode.FLASH, result.saleMode());
        verify(eventSessionMapper).updateSession(
                30L, EVENT_ID, "Session", "Hall", SessionSaleMode.FLASH,
                times.saleStart(), times.saleEnd(), times.start(), times.end());
    }

    private void assertCreateMode(SessionSaleMode requested, SessionSaleMode expected) {
        SessionTimes times = sessionTimes();
        AtomicReference<EventSessionEntity> inserted = new AtomicReference<>();
        when(eventMapper.selectByIdForUpdate(EVENT_ID))
                .thenReturn(event(OWNER_ID, EventStatus.DRAFT, "Title", null));
        when(eventSessionMapper.insert(any(EventSessionEntity.class))).thenAnswer(invocation -> {
            EventSessionEntity entity = invocation.getArgument(0);
            entity.setId(30L);
            inserted.set(entity);
            return 1;
        });
        when(eventSessionMapper.selectById(30L)).thenAnswer(invocation -> inserted.get());

        var result = eventService.createSession(OWNER_ID, EVENT_ID, new CreateEventSessionCommand(
                "Session", "Hall", requested,
                times.saleStart(), times.saleEnd(), times.start(), times.end()));

        ArgumentCaptor<EventSessionEntity> captor = ArgumentCaptor.forClass(EventSessionEntity.class);
        verify(eventSessionMapper).insert(captor.capture());
        assertEquals(expected, captor.getValue().getSaleMode());
        assertEquals(expected, result.saleMode());
        org.mockito.Mockito.reset(eventMapper, eventSessionMapper);
    }

    private static EventSessionEntity session(SessionSaleMode saleMode, SessionTimes times) {
        EventSessionEntity session = new EventSessionEntity();
        session.setId(30L);
        session.setEventId(EVENT_ID);
        session.setName("Session");
        session.setVenue("Hall");
        session.setSaleMode(saleMode);
        session.setSaleStartAt(times.saleStart());
        session.setSaleEndAt(times.saleEnd());
        session.setStartAt(times.start());
        session.setEndAt(times.end());
        return session;
    }

    private static SessionTimes sessionTimes() {
        LocalDateTime saleStart = LocalDateTime.of(2030, 5, 1, 9, 0);
        return new SessionTimes(saleStart, saleStart.plusDays(1),
                saleStart.plusDays(2), saleStart.plusDays(2).plusHours(2));
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

    private record SessionTimes(
            LocalDateTime saleStart,
            LocalDateTime saleEnd,
            LocalDateTime start,
            LocalDateTime end) {
    }
}

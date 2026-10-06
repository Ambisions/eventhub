package com.eventhub.event.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.eventhub.event.api.AdminEventReviewApi;
import com.eventhub.event.api.EventCheckInAuthorizationApi;
import com.eventhub.event.api.OrganizerEventApi;
import com.eventhub.event.api.OrganizerEventSessionGuardApi;
import com.eventhub.event.api.PublicEventQueryApi;
import com.eventhub.event.api.command.CreateEventCommand;
import com.eventhub.event.api.command.CreateEventSessionCommand;
import com.eventhub.event.api.command.RejectEventCommand;
import com.eventhub.event.api.command.UpdateEventCommand;
import com.eventhub.event.api.command.UpdateEventSessionCommand;
import com.eventhub.event.api.error.EventErrorCode;
import com.eventhub.event.api.error.EventException;
import com.eventhub.event.api.error.EventCheckInAuthorizationException;
import com.eventhub.event.api.error.EventCheckInAuthorizationFailure;
import com.eventhub.event.api.model.EventSessionView;
import com.eventhub.event.api.model.EditableEventSessionContext;
import com.eventhub.event.api.model.EventStatus;
import com.eventhub.event.api.model.ManagedEventView;
import com.eventhub.event.api.model.PublicEventView;
import com.eventhub.event.api.model.PublishedEventPage;
import com.eventhub.event.api.model.ReviewEventView;
import com.eventhub.event.api.model.SessionSaleMode;
import com.eventhub.event.domain.EventInputPolicy;
import com.eventhub.event.domain.EventCheckInPolicy;
import com.eventhub.event.domain.EventSessionInputPolicy;
import com.eventhub.event.domain.EventStatePolicy;
import com.eventhub.event.mapper.EventMapper;
import com.eventhub.event.mapper.EventSessionMapper;
import com.eventhub.event.repository.EventEntity;
import com.eventhub.event.repository.EventSessionEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

@Service
public class EventService implements PublicEventQueryApi, OrganizerEventApi, AdminEventReviewApi,
        OrganizerEventSessionGuardApi, EventCheckInAuthorizationApi {

    private static final int MAX_PAGE_SIZE = 100;

    private final EventMapper eventMapper;
    private final EventSessionMapper eventSessionMapper;

    public EventService(EventMapper eventMapper, EventSessionMapper eventSessionMapper) {
        this.eventMapper = eventMapper;
        this.eventSessionMapper = eventSessionMapper;
    }

    @Override
    public PublishedEventPage listPublishedEvents(int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new EventException(EventErrorCode.INVALID_EVENT);
        }
        long offset = Math.multiplyExact((long) page, size);
        List<EventEntity> rows = eventMapper.selectPublishedPage(offset, size + 1);
        boolean hasNext = rows.size() > size;
        int resultSize = Math.min(rows.size(), size);
        List<PublicEventView> items = new ArrayList<>(resultSize);
        for (int index = 0; index < resultSize; index++) {
            items.add(toPublicView(rows.get(index)));
        }
        return new PublishedEventPage(items, page, size, hasNext);
    }

    @Override
    public PublicEventView getPublishedEvent(long eventId) {
        validateEventId(eventId);
        EventEntity event = eventMapper.selectPublishedById(eventId);
        if (event == null) {
            throw new EventException(EventErrorCode.EVENT_NOT_FOUND);
        }
        return toPublicView(event);
    }

    @Override
    public List<EventSessionView> listPublishedSessions(long eventId) {
        validateEventId(eventId);
        if (eventMapper.selectPublishedById(eventId) == null) {
            throw new EventException(EventErrorCode.EVENT_NOT_FOUND);
        }
        return eventSessionMapper.selectPublishedByEventId(eventId).stream()
                .map(EventService::toSessionView)
                .toList();
    }

    @Override
    public EventSessionView getPublishedSession(long sessionId) {
        validateSessionId(sessionId);
        EventSessionEntity session = eventSessionMapper.selectPublishedById(sessionId);
        if (session == null) {
            throw new EventException(EventErrorCode.SESSION_NOT_FOUND);
        }
        return toSessionView(session);
    }

    @Override
    public void requireCheckInEligibility(
            long organizerUserId,
            long sessionId,
            java.time.LocalDateTime checkInTime) {
        if (organizerUserId <= 0 || sessionId <= 0) {
            throw checkInNotFound();
        }
        EventSessionEntity session = eventSessionMapper.selectOwnedCheckInSession(organizerUserId, sessionId);
        if (session == null) {
            throw checkInNotFound();
        }
        EventCheckInPolicy.requireEligible(checkInTime, session.getStartAt(), session.getEndAt());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public EditableEventSessionContext requireEditableOwnedSession(long organizerUserId, long sessionId) {
        validateUserId(organizerUserId);
        validateSessionId(sessionId);
        EventSessionEntity session = eventSessionMapper.selectById(sessionId);
        if (session == null) {
            throw new EventException(EventErrorCode.SESSION_NOT_FOUND);
        }
        requireEditableOwnedEventForUpdate(organizerUserId, session.getEventId());
        return new EditableEventSessionContext(sessionId, session.getEventId());
    }

    @Override
    public ManagedEventView getOwnedEvent(long currentUserId, long eventId) {
        validateUserId(currentUserId);
        validateEventId(eventId);
        EventEntity event = eventMapper.selectOwnedById(currentUserId, eventId);
        if (event == null) {
            throw new EventException(EventErrorCode.EVENT_NOT_FOUND);
        }
        return toManagedView(event);
    }

    @Override
    @Transactional
    public ManagedEventView createEvent(long currentUserId, CreateEventCommand command) {
        validateUserId(currentUserId);
        Objects.requireNonNull(command, "command must not be null");

        EventEntity event = new EventEntity();
        event.setOrganizerUserId(currentUserId);
        event.setTitle(EventInputPolicy.normalizeTitle(command.title()));
        event.setDescription(command.description());
        event.setStatus(EventStatus.DRAFT);
        event.setRejectionReason(null);
        eventMapper.insert(event);
        return toManagedView(requireEvent(event.getId()));
    }

    @Override
    @Transactional
    public ManagedEventView updateEvent(long currentUserId, long eventId, UpdateEventCommand command) {
        validateUserId(currentUserId);
        validateEventId(eventId);
        Objects.requireNonNull(command, "command must not be null");
        String title = EventInputPolicy.normalizeTitle(command.title());
        String description = command.description();

        int affectedRows = eventMapper.updateEditableEvent(currentUserId, eventId, title, description);
        if (affectedRows == 0) {
            EventEntity current = diagnoseOwnedEditableEvent(currentUserId, eventId);
            if (Objects.equals(current.getTitle(), title)
                    && Objects.equals(current.getDescription(), description)) {
                return toManagedView(current);
            }
            throw new EventException(EventErrorCode.EVENT_NOT_EDITABLE);
        }
        return toManagedView(requireEvent(eventId));
    }

    @Override
    @Transactional
    public EventSessionView createSession(
            long currentUserId,
            long eventId,
            CreateEventSessionCommand command) {
        validateUserId(currentUserId);
        validateEventId(eventId);
        Objects.requireNonNull(command, "command must not be null");
        requireEditableOwnedEventForUpdate(currentUserId, eventId);

        EventSessionEntity session = new EventSessionEntity();
        session.setEventId(eventId);
        session.setSaleMode(command.saleMode() == null ? SessionSaleMode.NORMAL : command.saleMode());
        applySessionInput(session, command.name(), command.venue(), command.saleStartAt(),
                command.saleEndAt(), command.startAt(), command.endAt());
        eventSessionMapper.insert(session);
        return toSessionView(requireSession(session.getId()));
    }

    @Override
    @Transactional
    public EventSessionView updateSession(
            long currentUserId,
            long sessionId,
            UpdateEventSessionCommand command) {
        validateUserId(currentUserId);
        validateSessionId(sessionId);
        Objects.requireNonNull(command, "command must not be null");

        EventSessionEntity existing = eventSessionMapper.selectById(sessionId);
        if (existing == null) {
            throw new EventException(EventErrorCode.SESSION_NOT_FOUND);
        }
        requireEditableOwnedEventForUpdate(currentUserId, existing.getEventId());

        EventSessionEntity update = new EventSessionEntity();
        applySessionInput(update, command.name(), command.venue(), command.saleStartAt(),
                command.saleEndAt(), command.startAt(), command.endAt());
        int affectedRows = eventSessionMapper.updateSession(
                sessionId,
                existing.getEventId(),
                update.getName(),
                update.getVenue(),
                command.saleMode(),
                update.getSaleStartAt(),
                update.getSaleEndAt(),
                update.getStartAt(),
                update.getEndAt());
        if (affectedRows == 0) {
            EventSessionEntity current = eventSessionMapper.selectById(sessionId);
            if (current == null) {
                throw new EventException(EventErrorCode.SESSION_NOT_FOUND);
            }
        }
        return toSessionView(requireSession(sessionId));
    }

    @Override
    @Transactional
    public ManagedEventView submitEvent(long currentUserId, long eventId) {
        validateUserId(currentUserId);
        validateEventId(eventId);
        if (eventMapper.submitEvent(currentUserId, eventId) == 0) {
            diagnoseOwnedTransition(currentUserId, eventId);
        }
        return toManagedView(requireEvent(eventId));
    }

    @Override
    public ReviewEventView getReviewEvent(long eventId) {
        validateEventId(eventId);
        EventEntity event = eventMapper.selectReviewById(eventId);
        if (event == null) {
            throw new EventException(EventErrorCode.EVENT_NOT_FOUND);
        }
        return toReviewView(event);
    }

    @Override
    @Transactional
    public ReviewEventView approveEvent(long eventId) {
        validateEventId(eventId);
        if (eventMapper.approveEvent(eventId) == 0) {
            diagnoseReviewTransition(eventId);
        }
        return toReviewView(requireEvent(eventId));
    }

    @Override
    @Transactional
    public ReviewEventView rejectEvent(long eventId, RejectEventCommand command) {
        validateEventId(eventId);
        Objects.requireNonNull(command, "command must not be null");
        String reason = EventInputPolicy.normalizeRejectionReason(command.reason());
        if (eventMapper.rejectEvent(eventId, reason) == 0) {
            diagnoseReviewTransition(eventId);
        }
        return toReviewView(requireEvent(eventId));
    }

    private EventEntity requireEditableOwnedEventForUpdate(long currentUserId, long eventId) {
        EventEntity event = eventMapper.selectByIdForUpdate(eventId);
        if (event == null || event.getOrganizerUserId() != currentUserId) {
            throw new EventException(EventErrorCode.EVENT_NOT_FOUND);
        }
        if (!EventStatePolicy.isEditable(event.getStatus())) {
            throw new EventException(EventErrorCode.EVENT_NOT_EDITABLE);
        }
        return event;
    }

    private EventEntity diagnoseOwnedEditableEvent(long currentUserId, long eventId) {
        EventEntity event = eventMapper.selectById(eventId);
        if (event == null || event.getOrganizerUserId() != currentUserId) {
            throw new EventException(EventErrorCode.EVENT_NOT_FOUND);
        }
        if (!EventStatePolicy.isEditable(event.getStatus())) {
            throw new EventException(EventErrorCode.EVENT_NOT_EDITABLE);
        }
        return event;
    }

    private void diagnoseOwnedTransition(long currentUserId, long eventId) {
        EventEntity event = eventMapper.selectById(eventId);
        if (event == null || event.getOrganizerUserId() != currentUserId) {
            throw new EventException(EventErrorCode.EVENT_NOT_FOUND);
        }
        throw new EventException(EventErrorCode.ILLEGAL_EVENT_STATE);
    }

    private void diagnoseReviewTransition(long eventId) {
        if (eventMapper.selectById(eventId) == null) {
            throw new EventException(EventErrorCode.EVENT_NOT_FOUND);
        }
        throw new EventException(EventErrorCode.ILLEGAL_EVENT_STATE);
    }

    private EventEntity requireEvent(long eventId) {
        EventEntity event = eventMapper.selectById(eventId);
        if (event == null) {
            throw new EventException(EventErrorCode.EVENT_NOT_FOUND);
        }
        return event;
    }

    private EventSessionEntity requireSession(long sessionId) {
        EventSessionEntity session = eventSessionMapper.selectById(sessionId);
        if (session == null) {
            throw new EventException(EventErrorCode.SESSION_NOT_FOUND);
        }
        return session;
    }

    private static void applySessionInput(
            EventSessionEntity session,
            String name,
            String venue,
            java.time.LocalDateTime saleStartAt,
            java.time.LocalDateTime saleEndAt,
            java.time.LocalDateTime startAt,
            java.time.LocalDateTime endAt) {
        EventSessionInputPolicy.validateTimes(saleStartAt, saleEndAt, startAt, endAt);
        session.setName(EventSessionInputPolicy.normalizeName(name));
        session.setVenue(EventSessionInputPolicy.normalizeVenue(venue));
        session.setSaleStartAt(saleStartAt);
        session.setSaleEndAt(saleEndAt);
        session.setStartAt(startAt);
        session.setEndAt(endAt);
    }

    private static void validateUserId(long userId) {
        if (userId <= 0) {
            throw new EventException(EventErrorCode.EVENT_NOT_FOUND);
        }
    }

    private static void validateEventId(long eventId) {
        if (eventId <= 0) {
            throw new EventException(EventErrorCode.EVENT_NOT_FOUND);
        }
    }

    private static void validateSessionId(long sessionId) {
        if (sessionId <= 0) {
            throw new EventException(EventErrorCode.SESSION_NOT_FOUND);
        }
    }

    private static EventCheckInAuthorizationException checkInNotFound() {
        return new EventCheckInAuthorizationException(
                EventCheckInAuthorizationFailure.NOT_FOUND_OR_NOT_OWNER);
    }

    private static PublicEventView toPublicView(EventEntity event) {
        return new PublicEventView(event.getId(), event.getTitle(), event.getDescription(),
                event.getStatus(), event.getCreatedAt(), event.getUpdatedAt());
    }

    private static ManagedEventView toManagedView(EventEntity event) {
        return new ManagedEventView(event.getId(), event.getTitle(), event.getDescription(),
                event.getStatus(), event.getRejectionReason(), event.getCreatedAt(), event.getUpdatedAt());
    }

    private static ReviewEventView toReviewView(EventEntity event) {
        return new ReviewEventView(event.getId(), event.getOrganizerUserId(), event.getTitle(),
                event.getDescription(), event.getStatus(), event.getRejectionReason(),
                event.getCreatedAt(), event.getUpdatedAt());
    }

    private static EventSessionView toSessionView(EventSessionEntity session) {
        return new EventSessionView(session.getId(), session.getEventId(), session.getName(),
                session.getVenue(), session.getSaleMode(), session.getSaleStartAt(), session.getSaleEndAt(),
                session.getStartAt(), session.getEndAt(), session.getCreatedAt(), session.getUpdatedAt());
    }
}

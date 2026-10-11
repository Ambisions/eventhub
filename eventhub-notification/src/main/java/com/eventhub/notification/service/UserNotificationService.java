package com.eventhub.notification.service;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Objects;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.eventhub.notification.api.event.TicketIssuedEvent;
import com.eventhub.notification.mapper.UserNotificationMapper;
import com.eventhub.notification.repository.UserNotificationEntity;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserNotificationService {

    private static final int MYSQL_DUPLICATE_KEY_ERROR_CODE = 1062;
    private static final String SQL_STATE_INTEGRITY_CONSTRAINT_VIOLATION = "23000";
    private static final String EVENT_UNIQUE_CONSTRAINT = "uk_user_notification_event";
    private static final String BUSINESS_UNIQUE_CONSTRAINT = "uk_user_notification_business";
    private static final String TICKET_ISSUED_TYPE = "TICKET_ISSUED";

    private final UserNotificationMapper userNotificationMapper;

    public UserNotificationService(UserNotificationMapper userNotificationMapper) {
        this.userNotificationMapper = userNotificationMapper;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void recordTicketIssued(TicketIssuedEvent event) {
        Objects.requireNonNull(event, "event must not be null");

        UserNotificationEntity notification = new UserNotificationEntity();
        notification.setEventId(event.eventId().toString());
        notification.setUserId(event.userId());
        notification.setType(TICKET_ISSUED_TYPE);
        notification.setBusinessReference(event.ticketNo());
        notification.setOccurredAt(toUtcMicros(event));

        int affectedRows = userNotificationMapper.insert(notification);
        if (affectedRows != 1 || notification.getId() == null) {
            throw new IllegalStateException("Notification insert did not create exactly one row");
        }
    }

    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public void verifyEquivalentDuplicate(TicketIssuedEvent event, DuplicateKeyException exception) {
        Objects.requireNonNull(event, "event must not be null");
        Objects.requireNonNull(exception, "exception must not be null");

        String constraint = duplicateConstraint(exception);
        if (constraint == null) {
            throw exception;
        }

        UserNotificationEntity byEventId = findByEventId(event.eventId().toString());
        UserNotificationEntity byBusinessIdentity = findByBusinessIdentity(event.ticketNo());

        if (EVENT_UNIQUE_CONSTRAINT.equals(constraint)) {
            verifyEventIdentity(event, byEventId, byBusinessIdentity, exception);
            return;
        }
        if (BUSINESS_UNIQUE_CONSTRAINT.equals(constraint)) {
            verifyBusinessIdentity(event, byEventId, byBusinessIdentity, exception);
            return;
        }
        throw exception;
    }

    private UserNotificationEntity findByEventId(String eventId) {
        return userNotificationMapper.selectOne(
                Wrappers.<UserNotificationEntity>lambdaQuery()
                        .eq(UserNotificationEntity::getEventId, eventId));
    }

    private UserNotificationEntity findByBusinessIdentity(String ticketNo) {
        return userNotificationMapper.selectOne(
                Wrappers.<UserNotificationEntity>lambdaQuery()
                        .eq(UserNotificationEntity::getType, TICKET_ISSUED_TYPE)
                        .eq(UserNotificationEntity::getBusinessReference, ticketNo));
    }

    private static void verifyEventIdentity(
            TicketIssuedEvent event,
            UserNotificationEntity byEventId,
            UserNotificationEntity byBusinessIdentity,
            DuplicateKeyException cause) {
        if (!eventIdentityEquivalent(event, byEventId)
                || !samePersistedRow(byEventId, byBusinessIdentity)) {
            throw conflictingDuplicate(cause);
        }
    }

    private static void verifyBusinessIdentity(
            TicketIssuedEvent event,
            UserNotificationEntity byEventId,
            UserNotificationEntity byBusinessIdentity,
            DuplicateKeyException cause) {
        if (!businessIdentityEquivalent(event, byBusinessIdentity)
                || (byEventId != null
                        && (!eventIdentityEquivalent(event, byEventId)
                                || !samePersistedRow(byEventId, byBusinessIdentity)))) {
            throw conflictingDuplicate(cause);
        }
    }

    private static boolean eventIdentityEquivalent(
            TicketIssuedEvent event,
            UserNotificationEntity existing) {
        return existing != null
                && Objects.equals(existing.getEventId(), event.eventId().toString())
                && Objects.equals(existing.getUserId(), event.userId())
                && Objects.equals(existing.getType(), TICKET_ISSUED_TYPE)
                && Objects.equals(existing.getBusinessReference(), event.ticketNo())
                && Objects.equals(existing.getOccurredAt(), toUtcMicros(event));
    }

    private static boolean businessIdentityEquivalent(
            TicketIssuedEvent event,
            UserNotificationEntity existing) {
        return existing != null
                && Objects.equals(existing.getUserId(), event.userId())
                && Objects.equals(existing.getType(), TICKET_ISSUED_TYPE)
                && Objects.equals(existing.getBusinessReference(), event.ticketNo());
    }

    private static boolean samePersistedRow(
            UserNotificationEntity first,
            UserNotificationEntity second) {
        return first != null
                && second != null
                && first.getId() != null
                && Objects.equals(first.getId(), second.getId());
    }

    private static LocalDateTime toUtcMicros(TicketIssuedEvent event) {
        return LocalDateTime.ofInstant(event.occurredAt(), ZoneOffset.UTC)
                .truncatedTo(ChronoUnit.MICROS);
    }

    private static String duplicateConstraint(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof SQLException sqlException
                    && sqlException.getErrorCode() == MYSQL_DUPLICATE_KEY_ERROR_CODE
                    && SQL_STATE_INTEGRITY_CONSTRAINT_VIOLATION.equals(sqlException.getSQLState())) {
                String message = sqlException.getMessage();
                if (message == null) {
                    return null;
                }
                String normalizedMessage = message.toLowerCase(Locale.ROOT);
                if (normalizedMessage.contains(EVENT_UNIQUE_CONSTRAINT)) {
                    return EVENT_UNIQUE_CONSTRAINT;
                }
                if (normalizedMessage.contains(BUSINESS_UNIQUE_CONSTRAINT)) {
                    return BUSINESS_UNIQUE_CONSTRAINT;
                }
                return null;
            }
            current = current.getCause();
        }
        return null;
    }

    private static IllegalStateException conflictingDuplicate(DuplicateKeyException cause) {
        return new IllegalStateException("Conflicting duplicate notification", cause);
    }
}

package com.eventhub.application.checkin;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

import com.eventhub.application.checkin.error.CheckInErrorCode;
import com.eventhub.application.checkin.error.CheckInException;
import com.eventhub.event.api.EventCheckInAuthorizationApi;
import com.eventhub.event.api.error.EventCheckInAuthorizationException;
import com.eventhub.ticket.api.TicketCheckInCommandApi;
import com.eventhub.ticket.api.TicketCheckInQueryApi;
import com.eventhub.ticket.api.error.TicketCheckInException;
import com.eventhub.ticket.api.model.TicketCheckInResult;
import com.eventhub.ticket.api.model.TicketCheckInTarget;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CheckInApplicationService {

    private final TicketCheckInQueryApi ticketCheckInQueryApi;
    private final EventCheckInAuthorizationApi eventCheckInAuthorizationApi;
    private final TicketCheckInCommandApi ticketCheckInCommandApi;
    private final Clock clock;

    public CheckInApplicationService(
            TicketCheckInQueryApi ticketCheckInQueryApi,
            EventCheckInAuthorizationApi eventCheckInAuthorizationApi,
            TicketCheckInCommandApi ticketCheckInCommandApi,
            Clock clock) {
        this.ticketCheckInQueryApi = ticketCheckInQueryApi;
        this.eventCheckInAuthorizationApi = eventCheckInAuthorizationApi;
        this.ticketCheckInCommandApi = ticketCheckInCommandApi;
        this.clock = clock;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public TicketCheckInResult checkIn(long currentUserId, String ticketNo) {
        LocalDateTime checkInTime = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
                .truncatedTo(ChronoUnit.MICROS);
        TicketCheckInTarget target = resolveTarget(ticketNo);
        requireEligibility(currentUserId, target.sessionId(), checkInTime);
        return markTicketUsed(target);
    }

    private TicketCheckInTarget resolveTarget(String ticketNo) {
        try {
            return ticketCheckInQueryApi.resolveTarget(ticketNo);
        } catch (TicketCheckInException exception) {
            if (exception.getFailure() == com.eventhub.ticket.api.error.TicketCheckInFailure.NOT_FOUND) {
                throw externalFailure(CheckInErrorCode.TICKET_NOT_FOUND, exception);
            }
            throw exception;
        }
    }

    private void requireEligibility(long currentUserId, long sessionId, LocalDateTime checkInTime) {
        try {
            eventCheckInAuthorizationApi.requireCheckInEligibility(currentUserId, sessionId, checkInTime);
        } catch (EventCheckInAuthorizationException exception) {
            CheckInErrorCode errorCode = switch (exception.getFailure()) {
                case NOT_FOUND_OR_NOT_OWNER -> CheckInErrorCode.TICKET_NOT_FOUND;
                case NOT_STARTED -> CheckInErrorCode.TICKET_CHECK_IN_NOT_STARTED;
                case CLOSED -> CheckInErrorCode.TICKET_CHECK_IN_CLOSED;
            };
            throw externalFailure(errorCode, exception);
        }
    }

    private TicketCheckInResult markTicketUsed(TicketCheckInTarget target) {
        try {
            return ticketCheckInCommandApi.checkIn(target.ticketNo(), target.sessionId());
        } catch (TicketCheckInException exception) {
            CheckInErrorCode errorCode = switch (exception.getFailure()) {
                case NOT_FOUND -> CheckInErrorCode.TICKET_NOT_FOUND;
                case ALREADY_USED -> CheckInErrorCode.TICKET_ALREADY_USED;
                case REFUNDED -> CheckInErrorCode.TICKET_REFUNDED;
            };
            throw externalFailure(errorCode, exception);
        }
    }

    private static CheckInException externalFailure(CheckInErrorCode errorCode, RuntimeException cause) {
        return new CheckInException(errorCode, cause);
    }
}

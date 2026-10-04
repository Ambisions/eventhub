package com.eventhub.application.checkin;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;

import com.eventhub.application.checkin.error.CheckInErrorCode;
import com.eventhub.application.checkin.error.CheckInException;
import com.eventhub.event.api.EventCheckInAuthorizationApi;
import com.eventhub.event.api.error.EventCheckInAuthorizationException;
import com.eventhub.event.api.error.EventCheckInAuthorizationFailure;
import com.eventhub.ticket.api.TicketCheckInCommandApi;
import com.eventhub.ticket.api.TicketCheckInQueryApi;
import com.eventhub.ticket.api.error.TicketCheckInException;
import com.eventhub.ticket.api.error.TicketCheckInFailure;
import com.eventhub.ticket.api.model.TicketCheckInResult;
import com.eventhub.ticket.api.model.TicketCheckInTarget;
import com.eventhub.ticket.api.model.TicketStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CheckInApplicationServiceTest {

    private static final long CURRENT_USER_ID = 41L;
    private static final long SESSION_ID = 82L;
    private static final String TICKET_NO = "TKT-00000000000000000000000000000001";
    private static final Instant NOW = Instant.parse("2030-06-01T02:00:00.123456789Z");
    private static final LocalDateTime CHECK_IN_TIME = LocalDateTime.parse("2030-06-01T02:00:00.123456");

    @Mock
    private TicketCheckInQueryApi ticketCheckInQueryApi;

    @Mock
    private EventCheckInAuthorizationApi eventCheckInAuthorizationApi;

    @Mock
    private TicketCheckInCommandApi ticketCheckInCommandApi;

    @Mock
    private Clock clock;

    private CheckInApplicationService service;

    @BeforeEach
    void setUp() {
        service = new CheckInApplicationService(
                ticketCheckInQueryApi,
                eventCheckInAuthorizationApi,
                ticketCheckInCommandApi,
                clock);
        when(clock.instant()).thenReturn(NOW);
    }

    @Test
    void capturesOneUtcMicrosecondTimeAndOrchestratesInFrozenOrder() {
        TicketCheckInTarget target = target();
        TicketCheckInResult expected = new TicketCheckInResult(TICKET_NO, SESSION_ID, TicketStatus.USED);
        when(ticketCheckInQueryApi.resolveTarget(TICKET_NO)).thenReturn(target);
        when(ticketCheckInCommandApi.checkIn(TICKET_NO, SESSION_ID)).thenReturn(expected);

        TicketCheckInResult actual = service.checkIn(CURRENT_USER_ID, TICKET_NO);

        assertSame(expected, actual);
        verify(clock, times(1)).instant();
        InOrder order = inOrder(ticketCheckInQueryApi, eventCheckInAuthorizationApi, ticketCheckInCommandApi);
        order.verify(ticketCheckInQueryApi).resolveTarget(TICKET_NO);
        order.verify(eventCheckInAuthorizationApi)
                .requireCheckInEligibility(CURRENT_USER_ID, SESSION_ID, CHECK_IN_TIME);
        order.verify(ticketCheckInCommandApi).checkIn(TICKET_NO, SESSION_ID);
    }

    @Test
    void translatesTargetNotFoundAndShortCircuitsAuthorizationAndMutation() {
        when(ticketCheckInQueryApi.resolveTarget(TICKET_NO))
                .thenThrow(new TicketCheckInException(TicketCheckInFailure.NOT_FOUND));

        assertExternalFailure(CheckInErrorCode.TICKET_NOT_FOUND);

        verifyNoInteractions(eventCheckInAuthorizationApi, ticketCheckInCommandApi);
    }

    @Test
    void translatesEventOwnershipFailureAndShortCircuitsMutation() {
        givenTarget();
        org.mockito.Mockito.doThrow(new EventCheckInAuthorizationException(
                        EventCheckInAuthorizationFailure.NOT_FOUND_OR_NOT_OWNER))
                .when(eventCheckInAuthorizationApi)
                .requireCheckInEligibility(CURRENT_USER_ID, SESSION_ID, CHECK_IN_TIME);

        assertExternalFailure(CheckInErrorCode.TICKET_NOT_FOUND);

        verify(ticketCheckInCommandApi, never()).checkIn(TICKET_NO, SESSION_ID);
    }

    @Test
    void translatesNotStartedAndShortCircuitsMutation() {
        givenTarget();
        org.mockito.Mockito.doThrow(new EventCheckInAuthorizationException(
                        EventCheckInAuthorizationFailure.NOT_STARTED))
                .when(eventCheckInAuthorizationApi)
                .requireCheckInEligibility(CURRENT_USER_ID, SESSION_ID, CHECK_IN_TIME);

        assertExternalFailure(CheckInErrorCode.TICKET_CHECK_IN_NOT_STARTED);

        verify(ticketCheckInCommandApi, never()).checkIn(TICKET_NO, SESSION_ID);
    }

    @Test
    void translatesClosedAndShortCircuitsMutation() {
        givenTarget();
        org.mockito.Mockito.doThrow(new EventCheckInAuthorizationException(
                        EventCheckInAuthorizationFailure.CLOSED))
                .when(eventCheckInAuthorizationApi)
                .requireCheckInEligibility(CURRENT_USER_ID, SESSION_ID, CHECK_IN_TIME);

        assertExternalFailure(CheckInErrorCode.TICKET_CHECK_IN_CLOSED);

        verify(ticketCheckInCommandApi, never()).checkIn(TICKET_NO, SESSION_ID);
    }

    @Test
    void translatesMutationNotFound() {
        givenTarget();
        when(ticketCheckInCommandApi.checkIn(TICKET_NO, SESSION_ID))
                .thenThrow(new TicketCheckInException(TicketCheckInFailure.NOT_FOUND));

        assertExternalFailure(CheckInErrorCode.TICKET_NOT_FOUND);
    }

    @Test
    void translatesAlreadyUsed() {
        givenTarget();
        when(ticketCheckInCommandApi.checkIn(TICKET_NO, SESSION_ID))
                .thenThrow(new TicketCheckInException(TicketCheckInFailure.ALREADY_USED));

        assertExternalFailure(CheckInErrorCode.TICKET_ALREADY_USED);
    }

    @Test
    void translatesRefundedTicket() {
        givenTarget();
        when(ticketCheckInCommandApi.checkIn(TICKET_NO, SESSION_ID))
                .thenThrow(new TicketCheckInException(TicketCheckInFailure.REFUNDED));

        assertExternalFailure(CheckInErrorCode.TICKET_REFUNDED);
    }

    @Test
    void preservesUnknownRuntimeFailure() {
        IllegalStateException failure = new IllegalStateException("synthetic unexpected failure");
        when(ticketCheckInQueryApi.resolveTarget(TICKET_NO)).thenThrow(failure);

        IllegalStateException actual = assertThrows(
                IllegalStateException.class,
                () -> service.checkIn(CURRENT_USER_ID, TICKET_NO));

        assertSame(failure, actual);
        verifyNoInteractions(eventCheckInAuthorizationApi, ticketCheckInCommandApi);
    }

    private void givenTarget() {
        when(ticketCheckInQueryApi.resolveTarget(TICKET_NO)).thenReturn(target());
    }

    private static TicketCheckInTarget target() {
        return new TicketCheckInTarget(TICKET_NO, SESSION_ID);
    }

    private void assertExternalFailure(CheckInErrorCode expected) {
        CheckInException exception = assertThrows(
                CheckInException.class,
                () -> service.checkIn(CURRENT_USER_ID, TICKET_NO));
        assertEquals(expected, exception.getErrorCode());
    }
}

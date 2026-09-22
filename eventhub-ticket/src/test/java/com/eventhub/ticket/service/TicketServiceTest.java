package com.eventhub.ticket.service;

import java.sql.SQLException;
import java.time.LocalDateTime;

import com.eventhub.ticket.api.command.IssueTicketCommand;
import com.eventhub.ticket.api.error.TicketErrorCode;
import com.eventhub.ticket.api.error.TicketException;
import com.eventhub.ticket.api.model.TicketStatus;
import com.eventhub.ticket.api.model.TicketView;
import com.eventhub.ticket.domain.TicketNumberGenerator;
import com.eventhub.ticket.mapper.TicketMapper;
import com.eventhub.ticket.repository.TicketEntity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TicketServiceTest {

    private static final String TICKET_NO = "TKT-0123456789ABCDEF0123456789ABCDEF";

    @Mock
    private TicketMapper ticketMapper;

    @Mock
    private TicketNumberGenerator ticketNumberGenerator;

    @InjectMocks
    private TicketService ticketService;

    @Test
    void validatesIssueCommandBeforePersistence() {
        assertThrows(NullPointerException.class, () -> ticketService.issueTicket(null));
        assertThrows(IllegalArgumentException.class,
                () -> ticketService.issueTicket(new IssueTicketCommand(0, 2, 3)));
        assertThrows(IllegalArgumentException.class,
                () -> ticketService.issueTicket(new IssueTicketCommand(1, 0, 3)));
        assertThrows(IllegalArgumentException.class,
                () -> ticketService.issueTicket(new IssueTicketCommand(1, 2, 0)));

        verifyNoInteractions(ticketMapper, ticketNumberGenerator);
    }

    @Test
    void issuesValidTicketAndMapsOnlyPublicViewFields() {
        LocalDateTime createdAt = LocalDateTime.of(2026, 9, 21, 12, 0);
        TicketEntity persisted = ticket(41L, 11L, 12L, 13L, TicketStatus.VALID, createdAt);
        when(ticketNumberGenerator.generate()).thenReturn(TICKET_NO);
        when(ticketMapper.insert(any(TicketEntity.class))).thenAnswer(invocation -> {
            TicketEntity inserted = invocation.getArgument(0);
            inserted.setId(41L);
            return 1;
        });
        when(ticketMapper.selectById(41L)).thenReturn(persisted);

        TicketView result = ticketService.issueTicket(new IssueTicketCommand(11L, 12L, 13L));

        assertEquals(TICKET_NO, result.ticketNo());
        assertEquals(11L, result.orderId());
        assertEquals(13L, result.sessionId());
        assertEquals(TicketStatus.VALID, result.status());
        assertEquals(createdAt, result.createdAt());
    }

    @Test
    void invalidTicketNumberReturnsNotFoundWithoutQueryingMapper() {
        assertTicketNotFound(() -> ticketService.getTicket(1L, null));
        assertTicketNotFound(() -> ticketService.getTicket(1L, "tkt-0123456789abcdef0123456789abcdef"));
        assertTicketNotFound(() -> ticketService.getTicket(0L, TICKET_NO));

        verifyNoInteractions(ticketMapper);
    }

    @Test
    void ownerQueryUsesMapperAndHidesMissingTicket() {
        when(ticketMapper.selectOne(any())).thenReturn(null);

        assertTicketNotFound(() -> ticketService.getTicket(7L, TICKET_NO));

        verify(ticketMapper).selectOne(any());
    }

    @Test
    void classifiesOrderUniqueViolationAsAlreadyIssued() {
        assertDuplicateClassification("uk_ticket_order", TicketErrorCode.TICKET_ALREADY_ISSUED);
    }

    @Test
    void classifiesUserSessionUniqueViolationAsAlreadyIssued() {
        assertDuplicateClassification("uk_ticket_user_session", TicketErrorCode.TICKET_ALREADY_ISSUED);
    }

    @Test
    void classifiesTicketNumberUniqueViolationAsIssuanceFailure() {
        assertDuplicateClassification("uk_ticket_ticket_no", TicketErrorCode.TICKET_ISSUANCE_FAILED);
    }

    @Test
    void preservesUnknownDuplicateKeyAsInfrastructureFailure() {
        DuplicateKeyException duplicate = duplicate("some_other_constraint");
        when(ticketNumberGenerator.generate()).thenReturn(TICKET_NO);
        doThrow(duplicate).when(ticketMapper).insert(any(TicketEntity.class));

        DuplicateKeyException actual = assertThrows(DuplicateKeyException.class,
                () -> ticketService.issueTicket(new IssueTicketCommand(11L, 12L, 13L)));

        assertSame(duplicate, actual);
        verify(ticketMapper, never()).selectById(any());
    }

    private void assertDuplicateClassification(String constraint, TicketErrorCode expected) {
        when(ticketNumberGenerator.generate()).thenReturn(TICKET_NO);
        doThrow(duplicate(constraint)).when(ticketMapper).insert(any(TicketEntity.class));

        TicketException exception = assertThrows(TicketException.class,
                () -> ticketService.issueTicket(new IssueTicketCommand(11L, 12L, 13L)));

        assertEquals(expected, exception.getErrorCode());
        verify(ticketMapper, never()).selectById(any());
    }

    private static DuplicateKeyException duplicate(String constraint) {
        SQLException sqlException = new SQLException(
                "Duplicate entry for key 'ticket." + constraint + "'", "23000", 1062);
        return new DuplicateKeyException("ticket insert failed", sqlException);
    }

    private static void assertTicketNotFound(Runnable invocation) {
        TicketException exception = assertThrows(TicketException.class, invocation::run);
        assertEquals(TicketErrorCode.TICKET_NOT_FOUND, exception.getErrorCode());
    }

    private static TicketEntity ticket(
            long id,
            long orderId,
            long userId,
            long sessionId,
            TicketStatus status,
            LocalDateTime createdAt) {
        TicketEntity ticket = new TicketEntity();
        ticket.setId(id);
        ticket.setTicketNo(TICKET_NO);
        ticket.setOrderId(orderId);
        ticket.setUserId(userId);
        ticket.setSessionId(sessionId);
        ticket.setStatus(status);
        ticket.setCreatedAt(createdAt);
        ticket.setUpdatedAt(createdAt);
        return ticket;
    }
}

package com.eventhub.ticket.api;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import com.eventhub.ticket.api.command.IssueTicketCommand;
import com.eventhub.ticket.api.command.RefundTicketCommand;
import com.eventhub.ticket.api.model.TicketCheckInResult;
import com.eventhub.ticket.api.model.TicketCheckInTarget;
import com.eventhub.ticket.api.model.TicketStatus;
import com.eventhub.ticket.api.model.TicketView;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TicketApiContractTest {

    @Test
    void publicModelsExposeOnlyFrozenFieldsAndStatuses() {
        Set<String> commandFields = Arrays.stream(IssueTicketCommand.class.getRecordComponents())
                .map(component -> component.getName())
                .collect(Collectors.toSet());
        Set<String> viewFields = Arrays.stream(TicketView.class.getRecordComponents())
                .map(component -> component.getName())
                .collect(Collectors.toSet());
        Set<String> refundCommandFields = Arrays.stream(RefundTicketCommand.class.getRecordComponents())
                .map(component -> component.getName())
                .collect(Collectors.toSet());
        Set<String> checkInTargetFields = Arrays.stream(TicketCheckInTarget.class.getRecordComponents())
                .map(component -> component.getName())
                .collect(Collectors.toSet());
        Set<String> checkInResultFields = Arrays.stream(TicketCheckInResult.class.getRecordComponents())
                .map(component -> component.getName())
                .collect(Collectors.toSet());

        assertEquals(Set.of("orderId", "userId", "sessionId"), commandFields);
        assertEquals(Set.of("orderId", "userId", "sessionId"), refundCommandFields);
        assertEquals(Set.of("ticketNo", "orderId", "sessionId", "status", "createdAt"), viewFields);
        assertEquals(Set.of("ticketNo", "sessionId"), checkInTargetFields);
        assertEquals(Set.of("ticketNo", "sessionId", "status"), checkInResultFields);
        assertEquals(Set.of("VALID", "USED", "REFUNDED"), Arrays.stream(TicketStatus.values())
                .map(Enum::name)
                .collect(Collectors.toSet()));
    }
}

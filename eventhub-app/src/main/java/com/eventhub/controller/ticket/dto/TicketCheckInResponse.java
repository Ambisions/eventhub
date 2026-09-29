package com.eventhub.controller.ticket.dto;

import com.eventhub.ticket.api.model.TicketCheckInResult;
import com.eventhub.ticket.api.model.TicketStatus;

public record TicketCheckInResponse(
        String ticketNo,
        long sessionId,
        TicketStatus status) {

    public static TicketCheckInResponse from(TicketCheckInResult result) {
        return new TicketCheckInResponse(result.ticketNo(), result.sessionId(), result.status());
    }
}

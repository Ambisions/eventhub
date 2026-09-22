package com.eventhub.controller.ticket.dto;

import java.time.LocalDateTime;

import com.eventhub.ticket.api.model.TicketStatus;
import com.eventhub.ticket.api.model.TicketView;

public record TicketResponse(
        String ticketNo,
        long orderId,
        long sessionId,
        TicketStatus status,
        LocalDateTime createdAt) {

    public static TicketResponse from(TicketView view) {
        return new TicketResponse(
                view.ticketNo(),
                view.orderId(),
                view.sessionId(),
                view.status(),
                view.createdAt());
    }
}

package com.eventhub.ticket.api.command;

public record RefundTicketCommand(
        long orderId,
        long userId,
        long sessionId) {
}

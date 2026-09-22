package com.eventhub.ticket.api.command;

public record IssueTicketCommand(
        long orderId,
        long userId,
        long sessionId) {
}

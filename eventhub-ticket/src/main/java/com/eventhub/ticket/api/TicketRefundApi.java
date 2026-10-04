package com.eventhub.ticket.api;

import com.eventhub.ticket.api.command.RefundTicketCommand;

public interface TicketRefundApi {

    void refundTicket(RefundTicketCommand command);
}

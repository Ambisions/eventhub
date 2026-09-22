package com.eventhub.ticket.api;

import com.eventhub.ticket.api.command.IssueTicketCommand;
import com.eventhub.ticket.api.model.TicketView;

public interface TicketIssuanceApi {

    TicketView issueTicket(IssueTicketCommand command);
}

package com.eventhub.ticket.api;

import com.eventhub.ticket.api.model.TicketView;

public interface TicketQueryApi {

    TicketView getTicket(long userId, String ticketNo);
}

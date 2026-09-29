package com.eventhub.ticket.api;

import com.eventhub.ticket.api.model.TicketCheckInResult;

public interface TicketCheckInCommandApi {

    TicketCheckInResult checkIn(String ticketNo, long sessionId);
}

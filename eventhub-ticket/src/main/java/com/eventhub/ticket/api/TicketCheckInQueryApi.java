package com.eventhub.ticket.api;

import com.eventhub.ticket.api.model.TicketCheckInTarget;

public interface TicketCheckInQueryApi {

    TicketCheckInTarget resolveTarget(String ticketNo);
}

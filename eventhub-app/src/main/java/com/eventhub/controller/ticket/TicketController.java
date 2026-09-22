package com.eventhub.controller.ticket;

import com.eventhub.controller.ticket.dto.TicketResponse;
import com.eventhub.security.EventHubAuthenticatedUser;
import com.eventhub.ticket.api.TicketQueryApi;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tickets")
public final class TicketController {

    private final TicketQueryApi ticketQueryApi;

    public TicketController(TicketQueryApi ticketQueryApi) {
        this.ticketQueryApi = ticketQueryApi;
    }

    @GetMapping("/{ticketNo}")
    public TicketResponse getTicket(
            @AuthenticationPrincipal EventHubAuthenticatedUser currentUser,
            @PathVariable String ticketNo) {
        return TicketResponse.from(ticketQueryApi.getTicket(currentUser.userId(), ticketNo));
    }
}

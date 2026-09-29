package com.eventhub.controller.ticket;

import com.eventhub.application.checkin.CheckInApplicationService;
import com.eventhub.controller.ticket.dto.TicketCheckInResponse;
import com.eventhub.controller.ticket.dto.TicketResponse;
import com.eventhub.security.EventHubAuthenticatedUser;
import com.eventhub.ticket.api.TicketQueryApi;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tickets")
public final class TicketController {

    private final TicketQueryApi ticketQueryApi;
    private final CheckInApplicationService checkInApplicationService;

    public TicketController(
            TicketQueryApi ticketQueryApi,
            CheckInApplicationService checkInApplicationService) {
        this.ticketQueryApi = ticketQueryApi;
        this.checkInApplicationService = checkInApplicationService;
    }

    @GetMapping("/{ticketNo}")
    public TicketResponse getTicket(
            @AuthenticationPrincipal EventHubAuthenticatedUser currentUser,
            @PathVariable String ticketNo) {
        return TicketResponse.from(ticketQueryApi.getTicket(currentUser.userId(), ticketNo));
    }

    @PostMapping("/{ticketNo}/check-in")
    public TicketCheckInResponse checkIn(
            @AuthenticationPrincipal EventHubAuthenticatedUser currentUser,
            @PathVariable String ticketNo) {
        return TicketCheckInResponse.from(checkInApplicationService.checkIn(currentUser.userId(), ticketNo));
    }
}

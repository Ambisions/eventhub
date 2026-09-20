package com.eventhub.controller.order;

import com.eventhub.controller.order.dto.OrderResponse;
import com.eventhub.order.api.ReservationApi;
import com.eventhub.security.EventHubAuthenticatedUser;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sessions")
public final class ReservationController {

    private final ReservationApi reservationApi;

    public ReservationController(ReservationApi reservationApi) {
        this.reservationApi = reservationApi;
    }

    @PostMapping("/{sessionId}/orders")
    @ResponseStatus(HttpStatus.CREATED)
    public OrderResponse reserve(
            @AuthenticationPrincipal EventHubAuthenticatedUser currentUser,
            @PathVariable long sessionId) {
        return OrderResponse.from(reservationApi.reserve(currentUser.userId(), sessionId));
    }
}

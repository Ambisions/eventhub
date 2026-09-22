package com.eventhub.order.api.model;

import com.eventhub.ticket.api.model.TicketView;

public record PaymentResult(
        OrderView order,
        TicketView ticket) {
}

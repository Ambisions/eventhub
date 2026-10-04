package com.eventhub.order.api.model;

public record RefundResult(
        long orderId,
        OrderStatus status) {
}

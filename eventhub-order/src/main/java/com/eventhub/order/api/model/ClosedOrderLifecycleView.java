package com.eventhub.order.api.model;

public record ClosedOrderLifecycleView(
        long orderId,
        long userId,
        long sessionId) {
}

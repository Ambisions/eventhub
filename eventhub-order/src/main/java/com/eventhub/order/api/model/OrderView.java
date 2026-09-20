package com.eventhub.order.api.model;

import java.time.LocalDateTime;

public record OrderView(
        long id,
        String orderNo,
        long sessionId,
        OrderStatus status,
        LocalDateTime expireAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}

package com.eventhub.controller.order.dto;

import java.time.LocalDateTime;

import com.eventhub.order.api.model.OrderStatus;
import com.eventhub.order.api.model.OrderView;

public record OrderResponse(
        long id,
        String orderNo,
        long sessionId,
        OrderStatus status,
        LocalDateTime expireAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public static OrderResponse from(OrderView view) {
        return new OrderResponse(
                view.id(),
                view.orderNo(),
                view.sessionId(),
                view.status(),
                view.expireAt(),
                view.createdAt(),
                view.updatedAt());
    }
}

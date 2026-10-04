package com.eventhub.controller.order.dto;

import com.eventhub.order.api.model.OrderStatus;
import com.eventhub.order.api.model.RefundResult;

public record RefundResponse(
        long orderId,
        OrderStatus status) {

    public static RefundResponse from(RefundResult result) {
        return new RefundResponse(result.orderId(), result.status());
    }
}

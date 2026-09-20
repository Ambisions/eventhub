package com.eventhub.order.api;

import com.eventhub.order.api.model.OrderView;

public interface OrderQueryApi {

    OrderView getOrder(long userId, long orderId);
}

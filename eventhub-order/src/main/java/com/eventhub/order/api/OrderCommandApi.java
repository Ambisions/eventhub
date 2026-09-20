package com.eventhub.order.api;

import com.eventhub.order.api.model.OrderView;

public interface OrderCommandApi {

    OrderView pay(long userId, long orderId);

    OrderView cancel(long userId, long orderId);
}

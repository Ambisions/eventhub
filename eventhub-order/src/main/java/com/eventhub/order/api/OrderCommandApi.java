package com.eventhub.order.api;

import com.eventhub.order.api.model.OrderView;
import com.eventhub.order.api.model.PaymentResult;

public interface OrderCommandApi {

    PaymentResult pay(long userId, long orderId);

    OrderView cancel(long userId, long orderId);
}

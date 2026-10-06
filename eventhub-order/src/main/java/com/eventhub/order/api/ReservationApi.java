package com.eventhub.order.api;

import com.eventhub.order.api.model.OrderView;

public interface ReservationApi {

    OrderView reserve(long userId, long sessionId);

    OrderView reserveFlash(long userId, long sessionId);
}

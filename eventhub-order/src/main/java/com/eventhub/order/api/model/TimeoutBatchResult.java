package com.eventhub.order.api.model;

import java.util.List;
import java.util.Objects;

public record TimeoutBatchResult(
        int scanned,
        int closed,
        List<ClosedOrderLifecycleView> closedOrders) {

    public TimeoutBatchResult {
        if (scanned < 0 || closed < 0 || closed > scanned) {
            throw new IllegalArgumentException("timeout batch counts are inconsistent");
        }
        closedOrders = List.copyOf(Objects.requireNonNull(closedOrders, "closedOrders must not be null"));
        if (closedOrders.size() != closed) {
            throw new IllegalArgumentException("closedOrders size must equal closed count");
        }
    }
}

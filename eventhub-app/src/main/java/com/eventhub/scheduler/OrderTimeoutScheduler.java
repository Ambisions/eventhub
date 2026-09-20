package com.eventhub.scheduler;

import com.eventhub.order.api.OrderTimeoutApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        prefix = "eventhub.order.timeout",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class OrderTimeoutScheduler {

    private final OrderTimeoutApi orderTimeoutApi;
    private final int batchSize;

    public OrderTimeoutScheduler(
            OrderTimeoutApi orderTimeoutApi,
            @Value("${eventhub.order.timeout.batch-size:100}") int batchSize) {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("eventhub.order.timeout.batch-size must be positive");
        }
        this.orderTimeoutApi = orderTimeoutApi;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${eventhub.order.timeout.fixed-delay-ms:5000}")
    public void processExpiredOrders() {
        orderTimeoutApi.processExpiredBatch(batchSize);
    }
}

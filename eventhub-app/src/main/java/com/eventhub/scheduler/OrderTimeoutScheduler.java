package com.eventhub.scheduler;

import com.eventhub.application.flash.FlashOrderLifecycleApplicationService;
import com.eventhub.order.api.OrderTimeoutApi;
import com.eventhub.order.api.model.TimeoutBatchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger LOGGER = LoggerFactory.getLogger(OrderTimeoutScheduler.class);

    private final OrderTimeoutApi orderTimeoutApi;
    private final FlashOrderLifecycleApplicationService flashOrderLifecycleApplicationService;
    private final int batchSize;

    public OrderTimeoutScheduler(
            OrderTimeoutApi orderTimeoutApi,
            FlashOrderLifecycleApplicationService flashOrderLifecycleApplicationService,
            @Value("${eventhub.order.timeout.batch-size:100}") int batchSize) {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("eventhub.order.timeout.batch-size must be positive");
        }
        this.orderTimeoutApi = orderTimeoutApi;
        this.flashOrderLifecycleApplicationService = flashOrderLifecycleApplicationService;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${eventhub.order.timeout.fixed-delay-ms:5000}")
    public void processExpiredOrders() {
        TimeoutBatchResult result = orderTimeoutApi.processExpiredBatch(batchSize);
        result.closedOrders().forEach(order -> {
            try {
                flashOrderLifecycleApplicationService.syncTimeoutClosed(order);
            } catch (RuntimeException exception) {
                LOGGER.warn(
                        "FLASH timeout lifecycle orchestration failed for orderId={} cause={}",
                        order.orderId(),
                        exception.getClass().getSimpleName());
            }
        });
    }
}

package com.eventhub.scheduler;

import com.eventhub.order.api.OrderTimeoutApi;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class OrderTimeoutSchedulerTest {

    @Test
    void delegatesConfiguredBatchSizeWithoutWaitingForTheRuntimeDelay() {
        OrderTimeoutApi orderTimeoutApi = mock(OrderTimeoutApi.class);
        OrderTimeoutScheduler scheduler = new OrderTimeoutScheduler(orderTimeoutApi, 37);

        scheduler.processExpiredOrders();

        verify(orderTimeoutApi).processExpiredBatch(37);
    }

    @Test
    void rejectsInvalidConfiguredBatchSize() {
        OrderTimeoutApi orderTimeoutApi = mock(OrderTimeoutApi.class);

        assertThrows(IllegalArgumentException.class, () -> new OrderTimeoutScheduler(orderTimeoutApi, 0));
    }
}

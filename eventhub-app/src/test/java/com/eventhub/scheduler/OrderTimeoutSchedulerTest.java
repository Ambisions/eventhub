package com.eventhub.scheduler;

import java.util.List;

import com.eventhub.application.flash.FlashOrderLifecycleApplicationService;
import com.eventhub.order.api.OrderTimeoutApi;
import com.eventhub.order.api.model.ClosedOrderLifecycleView;
import com.eventhub.order.api.model.TimeoutBatchResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderTimeoutSchedulerTest {

    @Test
    void delegatesConfiguredBatchSizeWithoutWaitingForTheRuntimeDelay() {
        OrderTimeoutApi orderTimeoutApi = mock(OrderTimeoutApi.class);
        FlashOrderLifecycleApplicationService lifecycleService = mock(FlashOrderLifecycleApplicationService.class);
        ClosedOrderLifecycleView first = new ClosedOrderLifecycleView(11L, 21L, 31L);
        ClosedOrderLifecycleView second = new ClosedOrderLifecycleView(12L, 22L, 32L);
        when(orderTimeoutApi.processExpiredBatch(37))
                .thenReturn(new TimeoutBatchResult(2, 2, List.of(first, second)));
        doThrow(new IllegalStateException("synthetic failure"))
                .when(lifecycleService).syncTimeoutClosed(first);
        OrderTimeoutScheduler scheduler = new OrderTimeoutScheduler(orderTimeoutApi, lifecycleService, 37);

        scheduler.processExpiredOrders();

        verify(orderTimeoutApi).processExpiredBatch(37);
        verify(lifecycleService).syncTimeoutClosed(first);
        verify(lifecycleService).syncTimeoutClosed(second);
    }

    @Test
    void rejectsInvalidConfiguredBatchSize() {
        OrderTimeoutApi orderTimeoutApi = mock(OrderTimeoutApi.class);
        FlashOrderLifecycleApplicationService lifecycleService = mock(FlashOrderLifecycleApplicationService.class);

        assertThrows(IllegalArgumentException.class,
                () -> new OrderTimeoutScheduler(orderTimeoutApi, lifecycleService, 0));
    }
}

package com.eventhub.order.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import com.eventhub.order.mapper.OrderMapper;
import com.eventhub.order.api.model.ClosedOrderLifecycleView;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderTimeoutServiceTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2036-07-08T09:10:11.123456789Z");

    @Test
    void rejectsNonPositiveBatchSizeBeforeReadingClockOrDatabase() {
        OrderMapper orderMapper = mock(OrderMapper.class);
        OrderTimeoutProcessor processor = mock(OrderTimeoutProcessor.class);
        CountingClock clock = new CountingClock(FIXED_INSTANT);
        OrderTimeoutService service = new OrderTimeoutService(orderMapper, processor, clock);

        assertThrows(IllegalArgumentException.class, () -> service.processExpiredBatch(0));
        assertThrows(IllegalArgumentException.class, () -> service.processExpiredBatch(-1));

        assertEquals(0, clock.instantCalls());
        verify(orderMapper, never()).findExpiredWaitPayIds(any(), anyInt());
    }

    @Test
    void usesOneTimeSnapshotOneSelectAndContinuesAfterFalseAndFailure() {
        OrderMapper orderMapper = mock(OrderMapper.class);
        OrderTimeoutProcessor processor = mock(OrderTimeoutProcessor.class);
        CountingClock clock = new CountingClock(FIXED_INSTANT);
        OrderTimeoutService service = new OrderTimeoutService(orderMapper, processor, clock);
        LocalDateTime expectedScanNow = LocalDateTime.ofInstant(FIXED_INSTANT, ZoneOffset.UTC)
                .truncatedTo(ChronoUnit.MICROS);
        when(orderMapper.findExpiredWaitPayIds(expectedScanNow, 4))
                .thenReturn(List.of(11L, 12L, 13L, 14L));
        ClosedOrderLifecycleView first = new ClosedOrderLifecycleView(11L, 101L, 201L);
        ClosedOrderLifecycleView second = new ClosedOrderLifecycleView(14L, 104L, 204L);
        when(processor.closeExpiredOrder(11L, expectedScanNow)).thenReturn(Optional.of(first));
        when(processor.closeExpiredOrder(12L, expectedScanNow)).thenReturn(Optional.empty());
        when(processor.closeExpiredOrder(13L, expectedScanNow))
                .thenThrow(new IllegalStateException("synthetic processor failure"));
        when(processor.closeExpiredOrder(14L, expectedScanNow)).thenReturn(Optional.of(second));

        var result = service.processExpiredBatch(4);

        assertEquals(4, result.scanned());
        assertEquals(2, result.closed());
        assertEquals(List.of(first, second), result.closedOrders());
        assertThrows(UnsupportedOperationException.class,
                () -> result.closedOrders().add(new ClosedOrderLifecycleView(15L, 105L, 205L)));
        assertEquals(1, clock.instantCalls());
        ArgumentCaptor<LocalDateTime> scanCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(orderMapper, times(1)).findExpiredWaitPayIds(scanCaptor.capture(), org.mockito.ArgumentMatchers.eq(4));
        assertEquals(expectedScanNow, scanCaptor.getValue());
        for (long orderId : List.of(11L, 12L, 13L, 14L)) {
            verify(processor).closeExpiredOrder(orderId, expectedScanNow);
        }
        verify(orderMapper, times(1)).findExpiredWaitPayIds(any(), anyInt());
    }

    private static final class CountingClock extends Clock {

        private final Instant fixedInstant;
        private final AtomicInteger instantCalls = new AtomicInteger();

        private CountingClock(Instant fixedInstant) {
            this.fixedInstant = fixedInstant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            instantCalls.incrementAndGet();
            return fixedInstant;
        }

        private int instantCalls() {
            return instantCalls.get();
        }
    }
}

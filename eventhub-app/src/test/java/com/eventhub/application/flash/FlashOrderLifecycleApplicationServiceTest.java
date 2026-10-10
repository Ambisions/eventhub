package com.eventhub.application.flash;

import java.time.LocalDateTime;

import com.eventhub.event.api.PublicEventQueryApi;
import com.eventhub.event.api.model.EventSessionView;
import com.eventhub.event.api.model.SessionSaleMode;
import com.eventhub.inventory.api.FlashOrderLifecycleApi;
import com.eventhub.inventory.api.command.FlashOrderLifecycleCommand;
import com.eventhub.inventory.api.model.FlashOrderLifecycleResult;
import com.eventhub.order.api.OrderCommandApi;
import com.eventhub.order.api.OrderQueryApi;
import com.eventhub.order.api.model.ClosedOrderLifecycleView;
import com.eventhub.order.api.model.OrderStatus;
import com.eventhub.order.api.model.OrderView;
import com.eventhub.order.api.model.RefundResult;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FlashOrderLifecycleApplicationServiceTest {

    @Test
    void cancelCommitsThroughOrderApiBeforeFlashRelease() {
        TestContext context = context();
        OrderView cancelled = order(OrderStatus.CANCELLED);
        when(context.orderCommandApi().cancel(2L, 3L)).thenReturn(cancelled);
        when(context.publicEventQueryApi().getPublishedSession(4L)).thenReturn(session(SessionSaleMode.FLASH));
        when(context.lifecycleApi().releaseActive(new FlashOrderLifecycleCommand(4L, 2L, 3L)))
                .thenReturn(FlashOrderLifecycleResult.RELEASED);

        assertSame(cancelled, context.service().cancel(2L, 3L));

        InOrder order = inOrder(context.orderCommandApi(), context.publicEventQueryApi(), context.lifecycleApi());
        order.verify(context.orderCommandApi()).cancel(2L, 3L);
        order.verify(context.publicEventQueryApi()).getPublishedSession(4L);
        order.verify(context.lifecycleApi()).releaseActive(new FlashOrderLifecycleCommand(4L, 2L, 3L));
    }

    @Test
    void cancelDbFailureDoesNotReadEventOrRedis() {
        TestContext context = context();
        IllegalStateException failure = new IllegalStateException("db failure");
        when(context.orderCommandApi().cancel(2L, 3L)).thenThrow(failure);

        assertSame(failure, assertThrows(IllegalStateException.class, () -> context.service().cancel(2L, 3L)));
        verify(context.publicEventQueryApi(), never()).getPublishedSession(anyLong());
        verify(context.lifecycleApi(), never()).releaseActive(any());
    }

    @Test
    void normalCancelDoesNotRequireRedis() {
        TestContext context = context();
        when(context.orderCommandApi().cancel(2L, 3L)).thenReturn(order(OrderStatus.CANCELLED));
        when(context.publicEventQueryApi().getPublishedSession(4L)).thenReturn(session(SessionSaleMode.NORMAL));

        context.service().cancel(2L, 3L);

        verify(context.lifecycleApi(), never()).releaseActive(any());
    }

    @Test
    void postCommitEventOrRedisFailureDoesNotReplaceCancelSuccess() {
        TestContext eventFailure = context();
        OrderView cancelled = order(OrderStatus.CANCELLED);
        when(eventFailure.orderCommandApi().cancel(2L, 3L)).thenReturn(cancelled);
        when(eventFailure.publicEventQueryApi().getPublishedSession(4L))
                .thenThrow(new IllegalStateException("event failure"));
        assertSame(cancelled, eventFailure.service().cancel(2L, 3L));

        TestContext redisFailure = context();
        when(redisFailure.orderCommandApi().cancel(2L, 3L)).thenReturn(cancelled);
        when(redisFailure.publicEventQueryApi().getPublishedSession(4L)).thenReturn(session(SessionSaleMode.FLASH));
        when(redisFailure.lifecycleApi().releaseActive(any())).thenThrow(new IllegalStateException("redis failure"));
        assertSame(cancelled, redisFailure.service().cancel(2L, 3L));
    }

    @Test
    void repeatedRefundAlwaysAttemptsIdempotentRedisRepair() {
        TestContext context = context();
        RefundResult refunded = new RefundResult(3L, OrderStatus.REFUNDED);
        when(context.orderCommandApi().refund(2L, 3L)).thenReturn(refunded);
        when(context.orderQueryApi().getOrder(2L, 3L)).thenReturn(order(OrderStatus.REFUNDED));
        when(context.publicEventQueryApi().getPublishedSession(4L)).thenReturn(session(SessionSaleMode.FLASH));
        when(context.lifecycleApi().refundActive(any()))
                .thenReturn(FlashOrderLifecycleResult.REFUNDED, FlashOrderLifecycleResult.IDEMPOTENT_REFUNDED);

        assertSame(refunded, context.service().refund(2L, 3L));
        assertSame(refunded, context.service().refund(2L, 3L));

        verify(context.lifecycleApi(), org.mockito.Mockito.times(2))
                .refundActive(new FlashOrderLifecycleCommand(4L, 2L, 3L));
    }

    @Test
    void refundPostCommitQueryFailurePreservesRefundResult() {
        TestContext context = context();
        RefundResult refunded = new RefundResult(3L, OrderStatus.REFUNDED);
        when(context.orderCommandApi().refund(2L, 3L)).thenReturn(refunded);
        when(context.orderQueryApi().getOrder(2L, 3L)).thenThrow(new IllegalStateException("query failure"));

        assertSame(refunded, context.service().refund(2L, 3L));
        verify(context.lifecycleApi(), never()).refundActive(any());
    }

    @Test
    void timeoutUsesExactCommittedMetadataAndSwallowsLifecycleFailure() {
        TestContext context = context();
        ClosedOrderLifecycleView closed = new ClosedOrderLifecycleView(3L, 2L, 4L);
        when(context.publicEventQueryApi().getPublishedSession(4L)).thenReturn(session(SessionSaleMode.FLASH));
        when(context.lifecycleApi().releaseActive(any())).thenThrow(new IllegalStateException("redis failure"));

        context.service().syncTimeoutClosed(closed);

        verify(context.lifecycleApi()).releaseActive(new FlashOrderLifecycleCommand(4L, 2L, 3L));
    }

    private static TestContext context() {
        OrderCommandApi commandApi = mock(OrderCommandApi.class);
        OrderQueryApi queryApi = mock(OrderQueryApi.class);
        PublicEventQueryApi eventApi = mock(PublicEventQueryApi.class);
        FlashOrderLifecycleApi lifecycleApi = mock(FlashOrderLifecycleApi.class);
        return new TestContext(
                new FlashOrderLifecycleApplicationService(commandApi, queryApi, eventApi, lifecycleApi),
                commandApi,
                queryApi,
                eventApi,
                lifecycleApi);
    }

    private static OrderView order(OrderStatus status) {
        LocalDateTime now = LocalDateTime.of(2036, 1, 2, 3, 4);
        return new OrderView(3L, "ORDER-3", 4L, status, now.plusMinutes(15), now, now);
    }

    private static EventSessionView session(SessionSaleMode saleMode) {
        LocalDateTime now = LocalDateTime.of(2036, 1, 2, 3, 4);
        return new EventSessionView(4L, 5L, "session", "venue", saleMode,
                now.minusMinutes(1), now.plusMinutes(1), now.plusHours(1), now.plusHours(2), now, now);
    }

    private record TestContext(
            FlashOrderLifecycleApplicationService service,
            OrderCommandApi orderCommandApi,
            OrderQueryApi orderQueryApi,
            PublicEventQueryApi publicEventQueryApi,
            FlashOrderLifecycleApi lifecycleApi) {
    }
}

package com.eventhub.order.service;

import java.time.LocalDateTime;

import com.eventhub.inventory.api.InventoryReservationApi;
import com.eventhub.inventory.api.error.InventoryErrorCode;
import com.eventhub.inventory.api.error.InventoryException;
import com.eventhub.order.api.model.OrderStatus;
import com.eventhub.order.mapper.OrderMapper;
import com.eventhub.order.repository.OrderEntity;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderTimeoutProcessorTest {

    private static final LocalDateTime SCAN_NOW = LocalDateTime.of(2036, 7, 8, 9, 10, 11, 123_456_000);

    @Test
    void returnsFalseWhenOrderIsMissing() {
        TestContext context = context();

        assertFalse(context.processor().closeExpiredOrder(91L, SCAN_NOW));

        verify(context.orderMapper(), never()).closeIfWaitPayAndExpired(91L, SCAN_NOW);
        verify(context.inventoryReservationApi(), never()).releaseOne(anyLong());
    }

    @Test
    void returnsFalseWithoutReleaseForNotExpiredAndTerminalOrders() {
        TestContext context = context();
        OrderStatus[] statuses = {
                OrderStatus.WAIT_PAY,
                OrderStatus.PAID,
                OrderStatus.CANCELLED,
                OrderStatus.CLOSED
        };
        long orderId = 100L;
        for (OrderStatus status : statuses) {
            OrderEntity order = order(orderId, 501L, status);
            when(context.orderMapper().selectOrderById(orderId)).thenReturn(order);
            when(context.orderMapper().closeIfWaitPayAndExpired(orderId, SCAN_NOW)).thenReturn(0);

            assertFalse(context.processor().closeExpiredOrder(orderId, SCAN_NOW));
            orderId++;
        }

        verify(context.inventoryReservationApi(), never()).releaseOne(anyLong());
        verify(context.orderMapper(), times(statuses.length))
                .closeIfWaitPayAndExpired(org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.eq(SCAN_NOW));
    }

    @Test
    void closesThenReleasesInventoryForTheConditionalUpdateWinner() {
        TestContext context = context();
        when(context.orderMapper().selectOrderById(91L))
                .thenReturn(order(91L, 501L, OrderStatus.WAIT_PAY));
        when(context.orderMapper().closeIfWaitPayAndExpired(91L, SCAN_NOW)).thenReturn(1);

        assertTrue(context.processor().closeExpiredOrder(91L, SCAN_NOW));

        InOrder order = inOrder(context.orderMapper(), context.inventoryReservationApi());
        order.verify(context.orderMapper()).selectOrderById(91L);
        order.verify(context.orderMapper()).closeIfWaitPayAndExpired(91L, SCAN_NOW);
        order.verify(context.inventoryReservationApi()).releaseOne(501L);
    }

    @Test
    void propagatesInventoryFailureSoTheTransactionCanRollBack() {
        TestContext context = context();
        when(context.orderMapper().selectOrderById(91L))
                .thenReturn(order(91L, 501L, OrderStatus.WAIT_PAY));
        when(context.orderMapper().closeIfWaitPayAndExpired(91L, SCAN_NOW)).thenReturn(1);
        InventoryException failure = new InventoryException(InventoryErrorCode.INVENTORY_STATE_CONFLICT);
        org.mockito.Mockito.doThrow(failure).when(context.inventoryReservationApi()).releaseOne(501L);

        InventoryException thrown = assertThrows(
                InventoryException.class,
                () -> context.processor().closeExpiredOrder(91L, SCAN_NOW));

        assertSame(failure, thrown);
    }

    private static TestContext context() {
        OrderMapper orderMapper = mock(OrderMapper.class);
        InventoryReservationApi inventoryReservationApi = mock(InventoryReservationApi.class);
        return new TestContext(
                new OrderTimeoutProcessor(orderMapper, inventoryReservationApi),
                orderMapper,
                inventoryReservationApi);
    }

    private static OrderEntity order(long orderId, long sessionId, OrderStatus status) {
        OrderEntity order = new OrderEntity();
        order.setId(orderId);
        order.setSessionId(sessionId);
        order.setStatus(status);
        return order;
    }

    private record TestContext(
            OrderTimeoutProcessor processor,
            OrderMapper orderMapper,
            InventoryReservationApi inventoryReservationApi) {
    }
}

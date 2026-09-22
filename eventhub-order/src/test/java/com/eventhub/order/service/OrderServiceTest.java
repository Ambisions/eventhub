package com.eventhub.order.service;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.eventhub.event.api.PublicEventQueryApi;
import com.eventhub.event.api.model.EventSessionView;
import com.eventhub.inventory.api.InventoryReservationApi;
import com.eventhub.inventory.api.error.InventoryErrorCode;
import com.eventhub.inventory.api.error.InventoryException;
import com.eventhub.order.api.error.OrderErrorCode;
import com.eventhub.order.api.error.OrderException;
import com.eventhub.order.api.model.OrderStatus;
import com.eventhub.order.mapper.OrderMapper;
import com.eventhub.order.repository.OrderEntity;
import com.eventhub.ticket.api.TicketIssuanceApi;
import com.eventhub.ticket.api.command.IssueTicketCommand;
import com.eventhub.ticket.api.error.TicketErrorCode;
import com.eventhub.ticket.api.error.TicketException;
import com.eventhub.ticket.api.model.TicketStatus;
import com.eventhub.ticket.api.model.TicketView;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.DuplicateKeyException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderServiceTest {

    private static final long USER_ID = 41L;
    private static final long SESSION_ID = 91L;
    private static final Instant FIXED_INSTANT = Instant.parse("2034-05-06T07:08:09.123456789Z");
    private static final LocalDateTime RESERVATION_NOW = LocalDateTime.ofInstant(FIXED_INSTANT, ZoneOffset.UTC)
            .truncatedTo(ChronoUnit.MICROS);

    @Test
    void reservesAtInclusiveSaleStartWithOneMicrosecondTimeSnapshotAndInsertBeforeInventoryLock() {
        TestContext context = contextAt(FIXED_INSTANT);
        when(context.publicEventQueryApi().getPublishedSession(SESSION_ID))
                .thenReturn(session(RESERVATION_NOW, RESERVATION_NOW.plusHours(1)));
        stubSuccessfulInsert(context.orderMapper());

        var result = context.service().reserve(USER_ID, SESSION_ID);

        ArgumentCaptor<OrderEntity> orderCaptor = ArgumentCaptor.forClass(OrderEntity.class);
        verify(context.orderMapper()).insertWaitPay(orderCaptor.capture());
        OrderEntity inserted = orderCaptor.getValue();
        assertEquals(OrderStatus.WAIT_PAY, inserted.getStatus());
        assertEquals(RESERVATION_NOW.plusMinutes(15), inserted.getExpireAt());
        assertEquals(0, inserted.getExpireAt().getNano() % 1_000);
        assertTrue(inserted.getOrderNo().matches("EH[0-9A-F]{32}"));
        assertEquals(inserted.getOrderNo(), result.orderNo());
        assertEquals(OrderStatus.WAIT_PAY, result.status());
        assertEquals(1, context.clock().instantCalls());

        InOrder order = inOrder(context.publicEventQueryApi(), context.orderMapper(),
                context.inventoryReservationApi());
        order.verify(context.publicEventQueryApi()).getPublishedSession(SESSION_ID);
        order.verify(context.orderMapper()).insertWaitPay(any(OrderEntity.class));
        order.verify(context.inventoryReservationApi()).lockOne(SESSION_ID);
        order.verify(context.orderMapper()).selectOrderById(501L);
    }

    @Test
    void rejectsBeforeSaleStart() {
        TestContext context = contextAt(FIXED_INSTANT);
        when(context.publicEventQueryApi().getPublishedSession(SESSION_ID))
                .thenReturn(session(RESERVATION_NOW.plusNanos(1_000), RESERVATION_NOW.plusHours(1)));

        OrderException exception = assertThrows(
                OrderException.class,
                () -> context.service().reserve(USER_ID, SESSION_ID));

        assertEquals(OrderErrorCode.SALE_NOT_STARTED, exception.getErrorCode());
        verify(context.orderMapper(), never()).insertWaitPay(any());
        verify(context.inventoryReservationApi(), never()).lockOne(SESSION_ID);
    }

    @Test
    void rejectsAtExactSaleEnd() {
        TestContext context = contextAt(FIXED_INSTANT);
        when(context.publicEventQueryApi().getPublishedSession(SESSION_ID))
                .thenReturn(session(RESERVATION_NOW.minusHours(1), RESERVATION_NOW));

        OrderException exception = assertThrows(
                OrderException.class,
                () -> context.service().reserve(USER_ID, SESSION_ID));

        assertEquals(OrderErrorCode.SALE_ENDED, exception.getErrorCode());
        verify(context.orderMapper(), never()).insertWaitPay(any());
    }

    @Test
    void rejectsAfterSaleEnd() {
        TestContext context = contextAt(FIXED_INSTANT);
        when(context.publicEventQueryApi().getPublishedSession(SESSION_ID))
                .thenReturn(session(RESERVATION_NOW.minusHours(2), RESERVATION_NOW.minusHours(1)));

        OrderException exception = assertThrows(
                OrderException.class,
                () -> context.service().reserve(USER_ID, SESSION_ID));

        assertEquals(OrderErrorCode.SALE_ENDED, exception.getErrorCode());
    }

    @Test
    void propagatesInventoryFailureAfterOrderInsert() {
        TestContext context = contextAt(FIXED_INSTANT);
        when(context.publicEventQueryApi().getPublishedSession(SESSION_ID))
                .thenReturn(session(RESERVATION_NOW.minusHours(1), RESERVATION_NOW.plusHours(1)));
        stubSuccessfulInsert(context.orderMapper());
        InventoryException inventoryFailure = new InventoryException(InventoryErrorCode.SOLD_OUT);
        org.mockito.Mockito.doThrow(inventoryFailure)
                .when(context.inventoryReservationApi()).lockOne(SESSION_ID);

        InventoryException thrown = assertThrows(
                InventoryException.class,
                () -> context.service().reserve(USER_ID, SESSION_ID));

        assertSame(inventoryFailure, thrown);
        verify(context.orderMapper(), never()).selectOrderById(501L);
    }

    @Test
    void translatesOnlyEffectivePurchaseDuplicate() {
        TestContext context = duplicateContext("uk_ticket_order_effective_purchase", 1062, "23000");

        OrderException exception = assertThrows(
                OrderException.class,
                () -> context.service().reserve(USER_ID, SESSION_ID));

        assertEquals(OrderErrorCode.ACTIVE_PURCHASE_EXISTS, exception.getErrorCode());
        assertInstanceOf(DuplicateKeyException.class, exception.getCause());
        verify(context.inventoryReservationApi(), never()).lockOne(SESSION_ID);
    }

    @Test
    void doesNotMisclassifyOrderNumberDuplicate() {
        TestContext context = duplicateContext("uk_ticket_order_order_no", 1062, "23000");

        assertThrows(DuplicateKeyException.class, () -> context.service().reserve(USER_ID, SESSION_ID));
        verify(context.inventoryReservationApi(), never()).lockOne(SESSION_ID);
    }

    @Test
    void rethrowsUnknownDuplicateIntegrityFailure() {
        TestContext context = duplicateContext("uk_ticket_order_effective_purchase", 1452, "23000");

        assertThrows(DuplicateKeyException.class, () -> context.service().reserve(USER_ID, SESSION_ID));
        verify(context.inventoryReservationApi(), never()).lockOne(SESSION_ID);
    }

    @Test
    void returnsOwnedOrderWithoutExposingPersistenceEntity() {
        TestContext context = contextAt(FIXED_INSTANT);
        OrderEntity order = order(501L, USER_ID, OrderStatus.WAIT_PAY, RESERVATION_NOW.plusMinutes(15));
        when(context.orderMapper().selectByIdAndUserId(501L, USER_ID)).thenReturn(order);

        var result = context.service().getOrder(USER_ID, 501L);

        assertEquals(501L, result.id());
        assertEquals(SESSION_ID, result.sessionId());
        assertEquals(OrderStatus.WAIT_PAY, result.status());
    }

    @Test
    void hidesMissingAndOtherOwnerOrdersAsNotFound() {
        TestContext context = contextAt(FIXED_INSTANT);

        OrderException missing = assertThrows(
                OrderException.class,
                () -> context.service().getOrder(USER_ID, 999L));
        OrderException invalid = assertThrows(
                OrderException.class,
                () -> context.service().getOrder(USER_ID, 0L));

        assertEquals(OrderErrorCode.ORDER_NOT_FOUND, missing.getErrorCode());
        assertEquals(OrderErrorCode.ORDER_NOT_FOUND, invalid.getErrorCode());
    }

    @Test
    void paysWithOneTimeSnapshotThenConfirmsInventoryAndReloads() {
        TestContext context = contextAt(FIXED_INSTANT);
        OrderEntity waitPay = order(501L, USER_ID, OrderStatus.WAIT_PAY, RESERVATION_NOW.plusMinutes(1));
        OrderEntity paid = order(501L, USER_ID, OrderStatus.PAID, RESERVATION_NOW.plusMinutes(1));
        TicketView ticket = ticket(501L, SESSION_ID);
        when(context.orderMapper().selectByIdAndUserId(501L, USER_ID)).thenReturn(waitPay, paid);
        when(context.orderMapper().markPaidIfWaitPayAndNotExpired(501L, USER_ID, RESERVATION_NOW))
                .thenReturn(1);
        when(context.ticketIssuanceApi().issueTicket(any())).thenReturn(ticket);

        var result = context.service().pay(USER_ID, 501L);

        assertEquals(OrderStatus.PAID, result.order().status());
        assertSame(ticket, result.ticket());
        assertEquals(1, context.clock().instantCalls());
        verify(context.publicEventQueryApi(), never()).getPublishedSession(anyLong());
        ArgumentCaptor<IssueTicketCommand> commandCaptor = ArgumentCaptor.forClass(IssueTicketCommand.class);
        InOrder order = inOrder(
                context.orderMapper(), context.inventoryReservationApi(), context.ticketIssuanceApi());
        order.verify(context.orderMapper()).markPaidIfWaitPayAndNotExpired(501L, USER_ID, RESERVATION_NOW);
        order.verify(context.inventoryReservationApi()).confirmSoldOne(SESSION_ID);
        order.verify(context.orderMapper()).selectByIdAndUserId(501L, USER_ID);
        order.verify(context.ticketIssuanceApi()).issueTicket(commandCaptor.capture());
        assertEquals(501L, commandCaptor.getValue().orderId());
        assertEquals(USER_ID, commandCaptor.getValue().userId());
        assertEquals(SESSION_ID, commandCaptor.getValue().sessionId());
    }

    @Test
    void doesNotIssueTicketWhenPaymentInventoryConfirmationFails() {
        TestContext context = contextAt(FIXED_INSTANT);
        OrderEntity waitPay = order(501L, USER_ID, OrderStatus.WAIT_PAY, RESERVATION_NOW.plusMinutes(1));
        when(context.orderMapper().selectByIdAndUserId(501L, USER_ID)).thenReturn(waitPay);
        when(context.orderMapper().markPaidIfWaitPayAndNotExpired(501L, USER_ID, RESERVATION_NOW))
                .thenReturn(1);
        InventoryException failure = new InventoryException(InventoryErrorCode.INVENTORY_STATE_CONFLICT);
        doThrow(failure).when(context.inventoryReservationApi()).confirmSoldOne(SESSION_ID);

        InventoryException thrown = assertThrows(
                InventoryException.class,
                () -> context.service().pay(USER_ID, 501L));

        assertSame(failure, thrown);
        verify(context.ticketIssuanceApi(), never()).issueTicket(any());
    }

    @Test
    void propagatesTicketExceptionAfterOrderAndInventorySteps() {
        TestContext context = contextAt(FIXED_INSTANT);
        OrderEntity waitPay = order(501L, USER_ID, OrderStatus.WAIT_PAY, RESERVATION_NOW.plusMinutes(1));
        OrderEntity paid = order(501L, USER_ID, OrderStatus.PAID, RESERVATION_NOW.plusMinutes(1));
        when(context.orderMapper().selectByIdAndUserId(501L, USER_ID)).thenReturn(waitPay, paid);
        when(context.orderMapper().markPaidIfWaitPayAndNotExpired(501L, USER_ID, RESERVATION_NOW))
                .thenReturn(1);
        TicketException failure = new TicketException(TicketErrorCode.TICKET_ISSUANCE_FAILED);
        when(context.ticketIssuanceApi().issueTicket(any())).thenThrow(failure);

        TicketException thrown = assertThrows(
                TicketException.class,
                () -> context.service().pay(USER_ID, 501L));

        assertSame(failure, thrown);
    }

    @Test
    void propagatesUnknownTicketRuntimeFailure() {
        TestContext context = contextAt(FIXED_INSTANT);
        OrderEntity waitPay = order(501L, USER_ID, OrderStatus.WAIT_PAY, RESERVATION_NOW.plusMinutes(1));
        OrderEntity paid = order(501L, USER_ID, OrderStatus.PAID, RESERVATION_NOW.plusMinutes(1));
        when(context.orderMapper().selectByIdAndUserId(501L, USER_ID)).thenReturn(waitPay, paid);
        when(context.orderMapper().markPaidIfWaitPayAndNotExpired(501L, USER_ID, RESERVATION_NOW))
                .thenReturn(1);
        RuntimeException failure = new RuntimeException("synthetic ticket infrastructure failure");
        when(context.ticketIssuanceApi().issueTicket(any())).thenThrow(failure);

        RuntimeException thrown = assertThrows(
                RuntimeException.class,
                () -> context.service().pay(USER_ID, 501L));

        assertSame(failure, thrown);
    }

    @Test
    void rejectsPaymentAtAndAfterExpirationWithoutInventoryMutation() {
        for (LocalDateTime expireAt : new LocalDateTime[]{RESERVATION_NOW, RESERVATION_NOW.minusNanos(1_000)}) {
            TestContext context = contextAt(FIXED_INSTANT);
            OrderEntity expired = order(501L, USER_ID, OrderStatus.WAIT_PAY, expireAt);
            when(context.orderMapper().selectByIdAndUserId(501L, USER_ID)).thenReturn(expired);
            when(context.orderMapper().markPaidIfWaitPayAndNotExpired(501L, USER_ID, RESERVATION_NOW))
                    .thenReturn(0);

            OrderException exception = assertThrows(
                    OrderException.class,
                    () -> context.service().pay(USER_ID, 501L));

            assertEquals(OrderErrorCode.ORDER_EXPIRED, exception.getErrorCode());
            assertEquals(1, context.clock().instantCalls());
            verify(context.inventoryReservationApi(), never()).confirmSoldOne(anyLong());
            verify(context.ticketIssuanceApi(), never()).issueTicket(any());
        }
    }

    @Test
    void rejectsPaymentForTerminalOrConcurrentlyChangedState() {
        for (OrderStatus status : new OrderStatus[]{OrderStatus.PAID, OrderStatus.CANCELLED, OrderStatus.CLOSED}) {
            TestContext context = contextAt(FIXED_INSTANT);
            OrderEntity order = order(501L, USER_ID, status, RESERVATION_NOW.plusMinutes(1));
            when(context.orderMapper().selectByIdAndUserId(501L, USER_ID)).thenReturn(order);
            when(context.orderMapper().markPaidIfWaitPayAndNotExpired(501L, USER_ID, RESERVATION_NOW))
                    .thenReturn(0);

            OrderException exception = assertThrows(
                    OrderException.class,
                    () -> context.service().pay(USER_ID, 501L));

            assertEquals(OrderErrorCode.ORDER_STATE_CONFLICT, exception.getErrorCode());
            verify(context.inventoryReservationApi(), never()).confirmSoldOne(anyLong());
            verify(context.ticketIssuanceApi(), never()).issueTicket(any());
        }

        TestContext staleContext = contextAt(FIXED_INSTANT);
        OrderEntity stale = order(501L, USER_ID, OrderStatus.WAIT_PAY, RESERVATION_NOW.plusMinutes(1));
        when(staleContext.orderMapper().selectByIdAndUserId(501L, USER_ID)).thenReturn(stale);
        when(staleContext.orderMapper().markPaidIfWaitPayAndNotExpired(501L, USER_ID, RESERVATION_NOW))
                .thenReturn(0);
        OrderException staleException = assertThrows(
                OrderException.class,
                () -> staleContext.service().pay(USER_ID, 501L));
        assertEquals(OrderErrorCode.ORDER_STATE_CONFLICT, staleException.getErrorCode());
    }

    @Test
    void cancelsExpiredWaitPayThenReleasesInventoryAndReloadsWithoutReadingClock() {
        TestContext context = contextAt(FIXED_INSTANT);
        OrderEntity waitPay = order(501L, USER_ID, OrderStatus.WAIT_PAY, RESERVATION_NOW.minusMinutes(1));
        OrderEntity cancelled = order(501L, USER_ID, OrderStatus.CANCELLED, RESERVATION_NOW.minusMinutes(1));
        when(context.orderMapper().selectByIdAndUserId(501L, USER_ID)).thenReturn(waitPay, cancelled);
        when(context.orderMapper().cancelIfWaitPay(501L, USER_ID)).thenReturn(1);

        var result = context.service().cancel(USER_ID, 501L);

        assertEquals(OrderStatus.CANCELLED, result.status());
        assertEquals(0, context.clock().instantCalls());
        InOrder order = inOrder(context.orderMapper(), context.inventoryReservationApi());
        order.verify(context.orderMapper()).cancelIfWaitPay(501L, USER_ID);
        order.verify(context.inventoryReservationApi()).releaseOne(SESSION_ID);
        order.verify(context.orderMapper()).selectByIdAndUserId(501L, USER_ID);
    }

    @Test
    void rejectsCancellationForTerminalStatesWithoutInventoryMutation() {
        for (OrderStatus status : new OrderStatus[]{OrderStatus.PAID, OrderStatus.CANCELLED, OrderStatus.CLOSED}) {
            TestContext context = contextAt(FIXED_INSTANT);
            OrderEntity order = order(501L, USER_ID, status, RESERVATION_NOW.plusMinutes(1));
            when(context.orderMapper().selectByIdAndUserId(501L, USER_ID)).thenReturn(order);
            when(context.orderMapper().cancelIfWaitPay(501L, USER_ID)).thenReturn(0);

            OrderException exception = assertThrows(
                    OrderException.class,
                    () -> context.service().cancel(USER_ID, 501L));

            assertEquals(OrderErrorCode.ORDER_STATE_CONFLICT, exception.getErrorCode());
            verify(context.inventoryReservationApi(), never()).releaseOne(anyLong());
        }
    }

    private static TestContext duplicateContext(String constraint, int vendorCode, String sqlState) {
        TestContext context = contextAt(FIXED_INSTANT);
        when(context.publicEventQueryApi().getPublishedSession(SESSION_ID))
                .thenReturn(session(RESERVATION_NOW.minusHours(1), RESERVATION_NOW.plusHours(1)));
        SQLException sqlException = new SQLException(
                "Duplicate entry for key '" + constraint + "'",
                sqlState,
                vendorCode);
        when(context.orderMapper().insertWaitPay(any()))
                .thenThrow(new DuplicateKeyException("duplicate", sqlException));
        return context;
    }

    private static TestContext contextAt(Instant instant) {
        PublicEventQueryApi publicEventQueryApi = mock(PublicEventQueryApi.class);
        InventoryReservationApi inventoryReservationApi = mock(InventoryReservationApi.class);
        TicketIssuanceApi ticketIssuanceApi = mock(TicketIssuanceApi.class);
        OrderMapper orderMapper = mock(OrderMapper.class);
        CountingClock clock = new CountingClock(Clock.fixed(instant, ZoneOffset.UTC));
        return new TestContext(
                new OrderService(
                        publicEventQueryApi, inventoryReservationApi, ticketIssuanceApi, orderMapper, clock),
                publicEventQueryApi,
                inventoryReservationApi,
                ticketIssuanceApi,
                orderMapper,
                clock);
    }

    private static void stubSuccessfulInsert(OrderMapper orderMapper) {
        AtomicReference<OrderEntity> insertedOrder = new AtomicReference<>();
        when(orderMapper.insertWaitPay(any())).thenAnswer(invocation -> {
            OrderEntity order = invocation.getArgument(0);
            order.setId(501L);
            order.setCreatedAt(RESERVATION_NOW);
            order.setUpdatedAt(RESERVATION_NOW);
            insertedOrder.set(order);
            return 1;
        });
        when(orderMapper.selectOrderById(501L)).thenAnswer(invocation -> insertedOrder.get());
    }

    private static OrderEntity order(
            long orderId,
            long userId,
            OrderStatus status,
            LocalDateTime expireAt) {
        OrderEntity order = new OrderEntity();
        order.setId(orderId);
        order.setOrderNo("EH0123456789ABCDEF0123456789ABCDEF");
        order.setUserId(userId);
        order.setSessionId(SESSION_ID);
        order.setStatus(status);
        order.setExpireAt(expireAt);
        order.setCreatedAt(RESERVATION_NOW.minusMinutes(1));
        order.setUpdatedAt(RESERVATION_NOW.minusMinutes(1));
        return order;
    }

    private static EventSessionView session(LocalDateTime saleStartAt, LocalDateTime saleEndAt) {
        return new EventSessionView(
                SESSION_ID,
                81L,
                "Session",
                null,
                saleStartAt,
                saleEndAt,
                saleEndAt.plusHours(1),
                saleEndAt.plusHours(2),
                RESERVATION_NOW.minusDays(1),
                RESERVATION_NOW.minusDays(1));
    }

    private static TicketView ticket(long orderId, long sessionId) {
        return new TicketView(
                "TKT-0123456789ABCDEF0123456789ABCDEF",
                orderId,
                sessionId,
                TicketStatus.VALID,
                RESERVATION_NOW);
    }

    private record TestContext(
            OrderService service,
            PublicEventQueryApi publicEventQueryApi,
            InventoryReservationApi inventoryReservationApi,
            TicketIssuanceApi ticketIssuanceApi,
            OrderMapper orderMapper,
            CountingClock clock) {
    }

    private static final class CountingClock extends Clock {

        private final Clock delegate;
        private final AtomicInteger instantCalls = new AtomicInteger();

        private CountingClock(Clock delegate) {
            this.delegate = delegate;
        }

        @Override
        public ZoneId getZone() {
            return delegate.getZone();
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new CountingClock(delegate.withZone(zone));
        }

        @Override
        public Instant instant() {
            instantCalls.incrementAndGet();
            return delegate.instant();
        }

        private int instantCalls() {
            return instantCalls.get();
        }
    }
}

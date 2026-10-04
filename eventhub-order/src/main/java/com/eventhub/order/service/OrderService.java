package com.eventhub.order.service;

import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.UUID;

import com.eventhub.event.api.PublicEventQueryApi;
import com.eventhub.event.api.model.EventSessionView;
import com.eventhub.inventory.api.InventoryReservationApi;
import com.eventhub.inventory.api.InventoryRefundApi;
import com.eventhub.inventory.api.error.InventoryException;
import com.eventhub.order.api.OrderCommandApi;
import com.eventhub.order.api.OrderQueryApi;
import com.eventhub.order.api.ReservationApi;
import com.eventhub.order.api.error.OrderErrorCode;
import com.eventhub.order.api.error.OrderException;
import com.eventhub.order.api.model.OrderStatus;
import com.eventhub.order.api.model.OrderView;
import com.eventhub.order.api.model.PaymentResult;
import com.eventhub.order.api.model.RefundResult;
import com.eventhub.order.mapper.OrderMapper;
import com.eventhub.order.repository.OrderEntity;
import com.eventhub.ticket.api.TicketIssuanceApi;
import com.eventhub.ticket.api.TicketRefundApi;
import com.eventhub.ticket.api.command.IssueTicketCommand;
import com.eventhub.ticket.api.command.RefundTicketCommand;
import com.eventhub.ticket.api.error.TicketRefundException;
import com.eventhub.ticket.api.error.TicketRefundFailure;
import com.eventhub.ticket.api.model.TicketView;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderService implements ReservationApi, OrderQueryApi, OrderCommandApi {

    private static final int PAYMENT_WINDOW_MINUTES = 15;
    private static final int MYSQL_DUPLICATE_KEY_ERROR_CODE = 1062;
    private static final String SQL_STATE_INTEGRITY_CONSTRAINT_VIOLATION = "23000";
    private static final String EFFECTIVE_PURCHASE_UNIQUE_CONSTRAINT =
            "uk_ticket_order_effective_purchase";

    private final PublicEventQueryApi publicEventQueryApi;
    private final InventoryReservationApi inventoryReservationApi;
    private final InventoryRefundApi inventoryRefundApi;
    private final TicketIssuanceApi ticketIssuanceApi;
    private final TicketRefundApi ticketRefundApi;
    private final OrderMapper orderMapper;
    private final Clock clock;

    public OrderService(
            PublicEventQueryApi publicEventQueryApi,
            InventoryReservationApi inventoryReservationApi,
            InventoryRefundApi inventoryRefundApi,
            TicketIssuanceApi ticketIssuanceApi,
            TicketRefundApi ticketRefundApi,
            OrderMapper orderMapper,
            Clock clock) {
        this.publicEventQueryApi = publicEventQueryApi;
        this.inventoryReservationApi = inventoryReservationApi;
        this.inventoryRefundApi = inventoryRefundApi;
        this.ticketIssuanceApi = ticketIssuanceApi;
        this.ticketRefundApi = ticketRefundApi;
        this.orderMapper = orderMapper;
        this.clock = clock;
    }

    @Override
    @Transactional
    public OrderView reserve(long userId, long sessionId) {
        if (userId <= 0) {
            throw new IllegalArgumentException("userId must be positive");
        }

        LocalDateTime reservationNow = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
                .truncatedTo(ChronoUnit.MICROS);
        EventSessionView session = publicEventQueryApi.getPublishedSession(sessionId);
        validateSaleWindow(session, reservationNow);

        OrderEntity order = new OrderEntity();
        order.setOrderNo(generateOrderNo());
        order.setUserId(userId);
        order.setSessionId(sessionId);
        order.setStatus(OrderStatus.WAIT_PAY);
        order.setExpireAt(reservationNow.plusMinutes(PAYMENT_WINDOW_MINUTES));

        insertWaitPay(order);
        inventoryReservationApi.lockOne(sessionId);

        OrderEntity persisted = orderMapper.selectOrderById(order.getId());
        if (persisted == null) {
            throw new IllegalStateException("Inserted order could not be reloaded");
        }
        return toView(persisted);
    }

    @Override
    public OrderView getOrder(long userId, long orderId) {
        return toView(requireOwnedOrder(userId, orderId));
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public PaymentResult pay(long userId, long orderId) {
        LocalDateTime paymentNow = currentUtcTime();
        OrderEntity current = requireOwnedOrder(userId, orderId);
        int affectedRows = orderMapper.markPaidIfWaitPayAndNotExpired(orderId, userId, paymentNow);
        if (affectedRows == 0) {
            throw classifyPayFailure(userId, orderId, paymentNow);
        }
        if (affectedRows != 1) {
            throw new IllegalStateException("Pay update affected an unexpected number of rows");
        }

        inventoryReservationApi.confirmSoldOne(current.getSessionId());
        OrderEntity paidOrder = requireOwnedOrder(userId, orderId);
        TicketView ticket = ticketIssuanceApi.issueTicket(new IssueTicketCommand(
                paidOrder.getId(),
                paidOrder.getUserId(),
                paidOrder.getSessionId()));
        return new PaymentResult(toView(paidOrder), ticket);
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public OrderView cancel(long userId, long orderId) {
        OrderEntity current = requireOwnedOrder(userId, orderId);
        int affectedRows = orderMapper.cancelIfWaitPay(orderId, userId);
        if (affectedRows == 0) {
            throw classifyStateFailure(userId, orderId);
        }
        if (affectedRows != 1) {
            throw new IllegalStateException("Cancel update affected an unexpected number of rows");
        }

        inventoryReservationApi.releaseOne(current.getSessionId());
        return toView(requireOwnedOrder(userId, orderId));
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public RefundResult refund(long userId, long orderId) {
        OrderEntity current = requireOwnedOrder(userId, orderId);
        if (current.getStatus() == OrderStatus.REFUNDED) {
            return refunded(orderId);
        }
        if (current.getStatus() != OrderStatus.PAID) {
            throw new OrderException(OrderErrorCode.ORDER_NOT_REFUNDABLE);
        }

        LocalDateTime refundTime = currentUtcTime();
        EventSessionView session = requireRefundSession(current.getSessionId());
        if (!refundTime.isBefore(session.startAt())) {
            throw new OrderException(OrderErrorCode.ORDER_REFUND_WINDOW_CLOSED);
        }

        int affectedRows = orderMapper.markRefundedIfPaid(orderId, userId, current.getSessionId());
        if (affectedRows == 0) {
            return classifyRefundUpdateLoss(userId, orderId);
        }
        if (affectedRows != 1) {
            throw refundInconsistent("Refund update affected an unexpected number of rows", null);
        }

        refundTicket(current);
        refundInventory(current.getSessionId());
        return refunded(orderId);
    }

    private void insertWaitPay(OrderEntity order) {
        try {
            int affectedRows = orderMapper.insertWaitPay(order);
            if (affectedRows != 1 || order.getId() == null) {
                throw new IllegalStateException("WAIT_PAY order insert did not produce one generated id");
            }
        } catch (DuplicateKeyException exception) {
            if (isEffectivePurchaseDuplicate(exception)) {
                throw new OrderException(OrderErrorCode.ACTIVE_PURCHASE_EXISTS, exception);
            }
            throw exception;
        }
    }

    private OrderEntity requireOwnedOrder(long userId, long orderId) {
        if (userId <= 0 || orderId <= 0) {
            throw new OrderException(OrderErrorCode.ORDER_NOT_FOUND);
        }
        OrderEntity order = orderMapper.selectByIdAndUserId(orderId, userId);
        if (order == null) {
            throw new OrderException(OrderErrorCode.ORDER_NOT_FOUND);
        }
        return order;
    }

    private OrderException classifyPayFailure(long userId, long orderId, LocalDateTime paymentNow) {
        OrderEntity latest = orderMapper.selectByIdAndUserId(orderId, userId);
        if (latest == null) {
            return new OrderException(OrderErrorCode.ORDER_NOT_FOUND);
        }
        if (latest.getStatus() == OrderStatus.WAIT_PAY
                && !latest.getExpireAt().isAfter(paymentNow)) {
            return new OrderException(OrderErrorCode.ORDER_EXPIRED);
        }
        return new OrderException(OrderErrorCode.ORDER_STATE_CONFLICT);
    }

    private OrderException classifyStateFailure(long userId, long orderId) {
        OrderEntity latest = orderMapper.selectByIdAndUserId(orderId, userId);
        return latest == null
                ? new OrderException(OrderErrorCode.ORDER_NOT_FOUND)
                : new OrderException(OrderErrorCode.ORDER_STATE_CONFLICT);
    }

    private EventSessionView requireRefundSession(long sessionId) {
        try {
            return publicEventQueryApi.getPublishedSession(sessionId);
        } catch (RuntimeException exception) {
            throw refundInconsistent("Refund session could not be resolved", exception);
        }
    }

    private RefundResult classifyRefundUpdateLoss(long userId, long orderId) {
        OrderEntity latest = orderMapper.selectByIdAndUserId(orderId, userId);
        if (latest != null && latest.getStatus() == OrderStatus.REFUNDED) {
            return refunded(orderId);
        }
        throw refundInconsistent("Refund update lost without a committed REFUNDED order", null);
    }

    private void refundTicket(OrderEntity order) {
        try {
            ticketRefundApi.refundTicket(new RefundTicketCommand(
                    order.getId(), order.getUserId(), order.getSessionId()));
        } catch (TicketRefundException exception) {
            if (exception.getFailure() == TicketRefundFailure.USED) {
                throw new OrderException(OrderErrorCode.ORDER_REFUND_TICKET_USED, exception);
            }
            throw refundInconsistent("Ticket state is inconsistent with refund processing", exception);
        }
    }

    private void refundInventory(long sessionId) {
        try {
            inventoryRefundApi.refundSoldOne(sessionId);
        } catch (InventoryException exception) {
            throw refundInconsistent("Inventory state is inconsistent with refund processing", exception);
        }
    }

    private static RefundResult refunded(long orderId) {
        return new RefundResult(orderId, OrderStatus.REFUNDED);
    }

    private static IllegalStateException refundInconsistent(String message, Throwable cause) {
        return cause == null ? new IllegalStateException(message) : new IllegalStateException(message, cause);
    }

    private LocalDateTime currentUtcTime() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
                .truncatedTo(ChronoUnit.MICROS);
    }

    private static void validateSaleWindow(EventSessionView session, LocalDateTime reservationNow) {
        if (reservationNow.isBefore(session.saleStartAt())) {
            throw new OrderException(OrderErrorCode.SALE_NOT_STARTED);
        }
        if (!reservationNow.isBefore(session.saleEndAt())) {
            throw new OrderException(OrderErrorCode.SALE_ENDED);
        }
    }

    private static boolean isEffectivePurchaseDuplicate(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof SQLException sqlException
                    && sqlException.getErrorCode() == MYSQL_DUPLICATE_KEY_ERROR_CODE
                    && SQL_STATE_INTEGRITY_CONSTRAINT_VIOLATION.equals(sqlException.getSQLState())) {
                String message = sqlException.getMessage();
                return message != null
                        && message.toLowerCase(Locale.ROOT).contains(EFFECTIVE_PURCHASE_UNIQUE_CONSTRAINT);
            }
            current = current.getCause();
        }
        return false;
    }

    private static String generateOrderNo() {
        return "EH" + UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
    }

    private static OrderView toView(OrderEntity order) {
        return new OrderView(
                order.getId(),
                order.getOrderNo(),
                order.getSessionId(),
                order.getStatus(),
                order.getExpireAt(),
                order.getCreatedAt(),
                order.getUpdatedAt());
    }
}

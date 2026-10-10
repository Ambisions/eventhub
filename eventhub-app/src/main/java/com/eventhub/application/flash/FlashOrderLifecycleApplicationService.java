package com.eventhub.application.flash;

import com.eventhub.event.api.PublicEventQueryApi;
import com.eventhub.event.api.model.SessionSaleMode;
import com.eventhub.inventory.api.FlashOrderLifecycleApi;
import com.eventhub.inventory.api.command.FlashOrderLifecycleCommand;
import com.eventhub.inventory.api.model.FlashOrderLifecycleResult;
import com.eventhub.order.api.OrderCommandApi;
import com.eventhub.order.api.OrderQueryApi;
import com.eventhub.order.api.model.ClosedOrderLifecycleView;
import com.eventhub.order.api.model.OrderView;
import com.eventhub.order.api.model.RefundResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public final class FlashOrderLifecycleApplicationService {

    private static final Logger LOGGER = LoggerFactory.getLogger(FlashOrderLifecycleApplicationService.class);

    private final OrderCommandApi orderCommandApi;
    private final OrderQueryApi orderQueryApi;
    private final PublicEventQueryApi publicEventQueryApi;
    private final FlashOrderLifecycleApi flashOrderLifecycleApi;

    public FlashOrderLifecycleApplicationService(
            OrderCommandApi orderCommandApi,
            OrderQueryApi orderQueryApi,
            PublicEventQueryApi publicEventQueryApi,
            FlashOrderLifecycleApi flashOrderLifecycleApi) {
        this.orderCommandApi = orderCommandApi;
        this.orderQueryApi = orderQueryApi;
        this.publicEventQueryApi = publicEventQueryApi;
        this.flashOrderLifecycleApi = flashOrderLifecycleApi;
    }

    public OrderView cancel(long userId, long orderId) {
        OrderView order = orderCommandApi.cancel(userId, orderId);
        syncReleaseAfterCommit(order.id(), userId, order.sessionId(), "cancel");
        return order;
    }

    public RefundResult refund(long userId, long orderId) {
        RefundResult result = orderCommandApi.refund(userId, orderId);
        try {
            OrderView order = orderQueryApi.getOrder(userId, orderId);
            syncRefundAfterCommit(order.id(), userId, order.sessionId());
        } catch (RuntimeException exception) {
            warnFailure("refund", orderId, userId, null, exception);
        }
        return result;
    }

    public void syncTimeoutClosed(ClosedOrderLifecycleView order) {
        if (order == null) {
            LOGGER.warn("FLASH timeout lifecycle sync skipped because lifecycle metadata was absent");
            return;
        }
        syncReleaseAfterCommit(order.orderId(), order.userId(), order.sessionId(), "timeout");
    }

    private void syncReleaseAfterCommit(long orderId, long userId, long sessionId, String operation) {
        try {
            if (!isFlash(sessionId)) {
                return;
            }
            FlashOrderLifecycleResult result = flashOrderLifecycleApi.releaseActive(
                    new FlashOrderLifecycleCommand(sessionId, userId, orderId));
            if (result != FlashOrderLifecycleResult.RELEASED
                    && result != FlashOrderLifecycleResult.IDEMPOTENT_RELEASED) {
                warnResult(operation, orderId, userId, sessionId, result);
            }
        } catch (RuntimeException exception) {
            warnFailure(operation, orderId, userId, sessionId, exception);
        }
    }

    private void syncRefundAfterCommit(long orderId, long userId, long sessionId) {
        try {
            if (!isFlash(sessionId)) {
                return;
            }
            FlashOrderLifecycleResult result = flashOrderLifecycleApi.refundActive(
                    new FlashOrderLifecycleCommand(sessionId, userId, orderId));
            if (result != FlashOrderLifecycleResult.REFUNDED
                    && result != FlashOrderLifecycleResult.IDEMPOTENT_REFUNDED) {
                warnResult("refund", orderId, userId, sessionId, result);
            }
        } catch (RuntimeException exception) {
            warnFailure("refund", orderId, userId, sessionId, exception);
        }
    }

    private boolean isFlash(long sessionId) {
        return publicEventQueryApi.getPublishedSession(sessionId).saleMode() == SessionSaleMode.FLASH;
    }

    private static void warnResult(
            String operation,
            long orderId,
            long userId,
            long sessionId,
            FlashOrderLifecycleResult result) {
        LOGGER.warn(
                "FLASH {} lifecycle sync did not complete for orderId={} userId={} sessionId={} result={}",
                operation,
                orderId,
                userId,
                sessionId,
                result);
    }

    private static void warnFailure(
            String operation,
            long orderId,
            long userId,
            Long sessionId,
            RuntimeException exception) {
        LOGGER.warn(
                "FLASH {} lifecycle sync failed after database success for orderId={} userId={} sessionId={} cause={}",
                operation,
                orderId,
                userId,
                sessionId,
                exception.getClass().getSimpleName());
    }
}

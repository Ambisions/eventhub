package com.eventhub.application.flash;

import com.eventhub.application.flash.error.FlashErrorCode;
import com.eventhub.application.flash.error.FlashException;
import com.eventhub.event.api.error.EventErrorCode;
import com.eventhub.event.api.error.EventException;
import com.eventhub.inventory.api.FlashAdmissionApi;
import com.eventhub.inventory.api.command.ActivateFlashAdmissionCommand;
import com.eventhub.inventory.api.command.ReleasePendingFlashAdmissionCommand;
import com.eventhub.inventory.api.command.ReserveFlashAdmissionCommand;
import com.eventhub.inventory.api.error.FlashAdmissionUnavailableException;
import com.eventhub.inventory.api.error.InventoryErrorCode;
import com.eventhub.inventory.api.error.InventoryException;
import com.eventhub.inventory.api.model.FlashAdmissionResult;
import com.eventhub.inventory.api.model.FlashReservationSettleResult;
import com.eventhub.order.api.ReservationApi;
import com.eventhub.order.api.error.OrderErrorCode;
import com.eventhub.order.api.error.OrderException;
import com.eventhub.order.api.model.OrderView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.CannotCreateTransactionException;

@Service
public final class FlashReservationApplicationService {

    private static final Logger LOGGER = LoggerFactory.getLogger(FlashReservationApplicationService.class);

    private final FlashAdmissionApi flashAdmissionApi;
    private final ReservationApi reservationApi;
    private final AdmissionIdGenerator admissionIdGenerator;

    public FlashReservationApplicationService(
            FlashAdmissionApi flashAdmissionApi,
            ReservationApi reservationApi,
            AdmissionIdGenerator admissionIdGenerator) {
        this.flashAdmissionApi = flashAdmissionApi;
        this.reservationApi = reservationApi;
        this.admissionIdGenerator = admissionIdGenerator;
    }

    public OrderView reserve(long userId, long sessionId) {
        String admissionId = admissionIdGenerator.generate();
        FlashAdmissionResult admissionResult = reserveAdmission(sessionId, userId, admissionId);
        if (admissionResult != FlashAdmissionResult.ADMITTED) {
            throw mapAdmissionFailure(admissionResult);
        }

        OrderView order = reserveInMySql(sessionId, userId, admissionId);
        activateBestEffort(sessionId, userId, admissionId, order.id());
        return order;
    }

    private FlashAdmissionResult reserveAdmission(long sessionId, long userId, String admissionId) {
        try {
            FlashAdmissionResult result = flashAdmissionApi.reserve(
                    new ReserveFlashAdmissionCommand(sessionId, userId, admissionId));
            if (result == null) {
                throw unavailable(null);
            }
            return result;
        } catch (FlashAdmissionUnavailableException exception) {
            throw unavailable(exception);
        }
    }

    private OrderView reserveInMySql(long sessionId, long userId, String admissionId) {
        try {
            return reservationApi.reserveFlash(userId, sessionId);
        } catch (CannotCreateTransactionException exception) {
            compensateTransactionNotStarted(sessionId, userId, admissionId);
            throw unavailable(exception);
        } catch (EventException exception) {
            if (exception.getErrorCode() == EventErrorCode.SESSION_NOT_FOUND) {
                compensateDeterministic(sessionId, userId, admissionId, exception);
            }
            throw unavailable(exception);
        } catch (OrderException exception) {
            if (isDeterministic(exception.getErrorCode())) {
                compensateDeterministic(sessionId, userId, admissionId, exception);
            }
            throw unavailable(exception);
        } catch (InventoryException exception) {
            if (exception.getErrorCode() == InventoryErrorCode.INVENTORY_NOT_CONFIGURED
                    || exception.getErrorCode() == InventoryErrorCode.SOLD_OUT) {
                compensateDeterministic(sessionId, userId, admissionId, exception);
            }
            throw unavailable(exception);
        } catch (RuntimeException exception) {
            throw unavailable(exception);
        }
    }

    private void compensateDeterministic(
            long sessionId,
            long userId,
            String admissionId,
            RuntimeException originalException) {
        final FlashReservationSettleResult result;
        try {
            result = flashAdmissionApi.releasePending(
                    new ReleasePendingFlashAdmissionCommand(sessionId, userId, admissionId));
        } catch (FlashAdmissionUnavailableException exception) {
            throw unavailable(exception);
        }
        if (result == FlashReservationSettleResult.RELEASED
                || result == FlashReservationSettleResult.IDEMPOTENT_RELEASED) {
            throw originalException;
        }
        throw unavailable(originalException);
    }

    private void compensateTransactionNotStarted(long sessionId, long userId, String admissionId) {
        try {
            FlashReservationSettleResult result = flashAdmissionApi.releasePending(
                    new ReleasePendingFlashAdmissionCommand(sessionId, userId, admissionId));
            if (result != FlashReservationSettleResult.RELEASED
                    && result != FlashReservationSettleResult.IDEMPOTENT_RELEASED) {
                LOGGER.warn(
                        "FLASH transaction-start compensation did not complete for sessionId={} userId={}",
                        sessionId,
                        userId);
            }
        } catch (FlashAdmissionUnavailableException exception) {
            LOGGER.warn(
                    "FLASH transaction-start compensation was unavailable for sessionId={} userId={}",
                    sessionId,
                    userId);
        }
    }

    private void activateBestEffort(long sessionId, long userId, String admissionId, long orderId) {
        try {
            FlashReservationSettleResult result = flashAdmissionApi.activate(
                    new ActivateFlashAdmissionCommand(sessionId, userId, admissionId, orderId));
            if (result != FlashReservationSettleResult.ACTIVATED
                    && result != FlashReservationSettleResult.IDEMPOTENT_ACTIVATED) {
                LOGGER.warn(
                        "FLASH admission activation did not complete for sessionId={} userId={} orderId={}",
                        sessionId,
                        userId,
                        orderId);
            }
        } catch (FlashAdmissionUnavailableException exception) {
            LOGGER.warn(
                    "FLASH admission activation was unavailable for sessionId={} userId={} orderId={}",
                    sessionId,
                    userId,
                    orderId);
        }
    }

    private static boolean isDeterministic(OrderErrorCode errorCode) {
        return errorCode == OrderErrorCode.RESERVATION_MODE_MISMATCH
                || errorCode == OrderErrorCode.SALE_NOT_STARTED
                || errorCode == OrderErrorCode.SALE_ENDED
                || errorCode == OrderErrorCode.ACTIVE_PURCHASE_EXISTS;
    }

    private static RuntimeException mapAdmissionFailure(FlashAdmissionResult result) {
        return switch (result) {
            case ALREADY_ADMITTED -> new FlashException(FlashErrorCode.FLASH_ALREADY_ADMITTED);
            case SOLD_OUT -> new InventoryException(InventoryErrorCode.SOLD_OUT);
            case NOT_STARTED -> new OrderException(OrderErrorCode.SALE_NOT_STARTED);
            case ENDED -> new OrderException(OrderErrorCode.SALE_ENDED);
            case IDEMPOTENT_PENDING, ALREADY_PENDING, NOT_PREPARED, STATE_CONFLICT -> unavailable(null);
            case ADMITTED -> throw new IllegalStateException("ADMITTED is not a failure result");
        };
    }

    private static FlashException unavailable(Throwable cause) {
        return cause == null
                ? new FlashException(FlashErrorCode.FLASH_SERVICE_UNAVAILABLE)
                : new FlashException(FlashErrorCode.FLASH_SERVICE_UNAVAILABLE, cause);
    }
}

package com.eventhub.handler;

import com.eventhub.application.checkin.error.CheckInErrorCode;
import com.eventhub.application.checkin.error.CheckInException;
import com.eventhub.application.flash.error.FlashPreparationErrorCode;
import com.eventhub.application.flash.error.FlashPreparationException;
import com.eventhub.auth.api.error.AuthErrorCode;
import com.eventhub.auth.api.error.AuthException;
import com.eventhub.event.api.error.EventErrorCode;
import com.eventhub.event.api.error.EventException;
import com.eventhub.inventory.api.error.InventoryErrorCode;
import com.eventhub.inventory.api.error.InventoryException;
import com.eventhub.order.api.error.OrderException;
import com.eventhub.order.api.error.OrderErrorCode;
import com.eventhub.ticket.api.error.TicketErrorCode;
import com.eventhub.ticket.api.error.TicketException;
import com.eventhub.user.api.error.UserErrorCode;
import com.eventhub.user.api.error.UserException;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public final class GlobalExceptionHandler {

    @ExceptionHandler(AuthException.class)
    public ResponseEntity<ApiErrorResponse> handleAuthException(AuthException exception) {
        HttpStatus status = exception.getErrorCode() == AuthErrorCode.INVALID_CREDENTIALS
                ? HttpStatus.UNAUTHORIZED
                : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status).body(new ApiErrorResponse(
                exception.getErrorCode().getCode(),
                exception.getMessage()));
    }

    @ExceptionHandler(UserException.class)
    public ResponseEntity<ApiErrorResponse> handleUserException(UserException exception) {
        HttpStatus status = statusFor(exception.getErrorCode());
        String code = status == HttpStatus.INTERNAL_SERVER_ERROR
                ? "INTERNAL_ERROR"
                : exception.getErrorCode().getCode();
        String message = status == HttpStatus.INTERNAL_SERVER_ERROR
                ? "An internal error occurred"
                : exception.getMessage();
        return ResponseEntity.status(status).body(new ApiErrorResponse(code, message));
    }

    @ExceptionHandler(EventException.class)
    public ResponseEntity<ApiErrorResponse> handleEventException(EventException exception) {
        HttpStatus status = statusFor(exception.getErrorCode());
        return ResponseEntity.status(status).body(new ApiErrorResponse(
                exception.getErrorCode().getCode(),
                exception.getMessage()));
    }

    @ExceptionHandler(InventoryException.class)
    public ResponseEntity<ApiErrorResponse> handleInventoryException(InventoryException exception) {
        HttpStatus status = statusFor(exception.getErrorCode());
        return ResponseEntity.status(status).body(new ApiErrorResponse(
                exception.getErrorCode().getCode(), exception.getMessage()));
    }

    @ExceptionHandler(OrderException.class)
    public ResponseEntity<ApiErrorResponse> handleOrderException(OrderException exception) {
        return ResponseEntity.status(statusFor(exception.getErrorCode())).body(new ApiErrorResponse(
                exception.getErrorCode().getCode(), exception.getMessage()));
    }

    @ExceptionHandler(TicketException.class)
    public ResponseEntity<ApiErrorResponse> handleTicketException(TicketException exception) {
        return ResponseEntity.status(statusFor(exception.getErrorCode())).body(new ApiErrorResponse(
                exception.getErrorCode().getCode(), exception.getMessage()));
    }

    @ExceptionHandler(CheckInException.class)
    public ResponseEntity<ApiErrorResponse> handleCheckInException(CheckInException exception) {
        return ResponseEntity.status(statusFor(exception.getErrorCode())).body(new ApiErrorResponse(
                exception.getErrorCode().getCode(), exception.getMessage()));
    }

    @ExceptionHandler(FlashPreparationException.class)
    public ResponseEntity<ApiErrorResponse> handleFlashPreparationException(
            FlashPreparationException exception) {
        return ResponseEntity.status(statusFor(exception.getErrorCode())).body(new ApiErrorResponse(
                exception.getErrorCode().getCode(), exception.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidationException(MethodArgumentNotValidException exception) {
        return ResponseEntity.badRequest().body(
                new ApiErrorResponse("VALIDATION_ERROR", "Request validation failed"));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleUnreadableRequest(HttpMessageNotReadableException exception) {
        return ResponseEntity.badRequest().body(
                new ApiErrorResponse("INVALID_REQUEST", "Request body is missing or malformed"));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleConstraintViolation(ConstraintViolationException exception) {
        return ResponseEntity.badRequest().body(
                new ApiErrorResponse("VALIDATION_ERROR", "Request validation failed"));
    }

    private static HttpStatus statusFor(UserErrorCode errorCode) {
        return switch (errorCode) {
            case USER_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case USERNAME_ALREADY_EXISTS -> HttpStatus.CONFLICT;
            case INVALID_USERNAME, INVALID_DISPLAY_NAME -> HttpStatus.BAD_REQUEST;
            case INVALID_ENCODED_PASSWORD -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }

    private static HttpStatus statusFor(EventErrorCode errorCode) {
        return switch (errorCode) {
            case EVENT_NOT_FOUND, SESSION_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case EVENT_NOT_EDITABLE, ILLEGAL_EVENT_STATE -> HttpStatus.CONFLICT;
            case INVALID_EVENT, INVALID_SESSION, INVALID_REJECTION_REASON -> HttpStatus.BAD_REQUEST;
        };
    }

    private static HttpStatus statusFor(InventoryErrorCode errorCode) {
        return switch (errorCode) {
            case INVALID_INVENTORY_TOTAL -> HttpStatus.BAD_REQUEST;
            case INVENTORY_NOT_CONFIGURED, SOLD_OUT, INVENTORY_STATE_CONFLICT, INVENTORY_ALREADY_IN_USE ->
                    HttpStatus.CONFLICT;
        };
    }

    private static HttpStatus statusFor(OrderErrorCode errorCode) {
        return switch (errorCode) {
            case ORDER_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case ACTIVE_PURCHASE_EXISTS, SALE_NOT_STARTED, SALE_ENDED, RESERVATION_MODE_MISMATCH,
                    ORDER_EXPIRED, ORDER_STATE_CONFLICT, ORDER_NOT_REFUNDABLE,
                    ORDER_REFUND_WINDOW_CLOSED, ORDER_REFUND_TICKET_USED -> HttpStatus.CONFLICT;
        };
    }

    private static HttpStatus statusFor(TicketErrorCode errorCode) {
        return switch (errorCode) {
            case TICKET_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case TICKET_ALREADY_ISSUED -> HttpStatus.CONFLICT;
            case TICKET_ISSUANCE_FAILED -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }

    private static HttpStatus statusFor(CheckInErrorCode errorCode) {
        return switch (errorCode) {
            case TICKET_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case TICKET_CHECK_IN_NOT_STARTED, TICKET_CHECK_IN_CLOSED, TICKET_ALREADY_USED,
                    TICKET_REFUNDED ->
                    HttpStatus.CONFLICT;
        };
    }

    private static HttpStatus statusFor(FlashPreparationErrorCode errorCode) {
        return switch (errorCode) {
            case FLASH_PREPARATION_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case FLASH_PREPARATION_NOT_ALLOWED, FLASH_PREPARATION_WINDOW_CLOSED,
                    FLASH_PREPARATION_STATE_CONFLICT -> HttpStatus.CONFLICT;
            case FLASH_SERVICE_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
        };
    }
}

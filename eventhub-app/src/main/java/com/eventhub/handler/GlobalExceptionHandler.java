package com.eventhub.handler;

import com.eventhub.auth.api.error.AuthErrorCode;
import com.eventhub.auth.api.error.AuthException;
import com.eventhub.event.api.error.EventErrorCode;
import com.eventhub.event.api.error.EventException;
import com.eventhub.inventory.api.error.InventoryErrorCode;
import com.eventhub.inventory.api.error.InventoryException;
import com.eventhub.order.api.error.OrderException;
import com.eventhub.order.api.error.OrderErrorCode;
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
            case ACTIVE_PURCHASE_EXISTS, SALE_NOT_STARTED, SALE_ENDED,
                    ORDER_EXPIRED, ORDER_STATE_CONFLICT -> HttpStatus.CONFLICT;
        };
    }
}

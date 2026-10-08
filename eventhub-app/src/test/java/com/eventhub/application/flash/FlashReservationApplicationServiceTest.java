package com.eventhub.application.flash;

import java.time.LocalDateTime;
import java.util.List;

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
import com.eventhub.order.api.model.OrderStatus;
import com.eventhub.order.api.model.OrderView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FlashReservationApplicationServiceTest {

    private static final long USER_ID = 41L;
    private static final long SESSION_ID = 73L;
    private static final String ADMISSION_ID = "11111111-2222-3333-4444-555555555555";

    @Mock
    private FlashAdmissionApi flashAdmissionApi;

    @Mock
    private ReservationApi reservationApi;

    @Mock
    private AdmissionIdGenerator admissionIdGenerator;

    private FlashReservationApplicationService service;

    @BeforeEach
    void setUp() {
        service = new FlashReservationApplicationService(
                flashAdmissionApi, reservationApi, admissionIdGenerator);
        when(admissionIdGenerator.generate()).thenReturn(ADMISSION_ID);
    }

    @Test
    void admittedReservesInMySqlThenActivatesAndReturnsCommittedOrder() {
        OrderView order = order();
        when(flashAdmissionApi.reserve(reserveCommand())).thenReturn(FlashAdmissionResult.ADMITTED);
        when(reservationApi.reserveFlash(USER_ID, SESSION_ID)).thenReturn(order);
        when(flashAdmissionApi.activate(activateCommand(order.id())))
                .thenReturn(FlashReservationSettleResult.ACTIVATED);

        assertThat(service.reserve(USER_ID, SESSION_ID)).isEqualTo(order);

        InOrder sequence = inOrder(admissionIdGenerator, flashAdmissionApi, reservationApi);
        sequence.verify(admissionIdGenerator).generate();
        sequence.verify(flashAdmissionApi).reserve(reserveCommand());
        sequence.verify(reservationApi).reserveFlash(USER_ID, SESSION_ID);
        sequence.verify(flashAdmissionApi).activate(activateCommand(order.id()));
        verify(flashAdmissionApi, never()).releasePending(any());
    }

    @Test
    void everyRequestGeneratesExactlyOneAdmissionId() {
        when(flashAdmissionApi.reserve(any())).thenReturn(FlashAdmissionResult.SOLD_OUT);

        assertThatThrownBy(() -> service.reserve(USER_ID, SESSION_ID)).isInstanceOf(InventoryException.class);
        assertThatThrownBy(() -> service.reserve(USER_ID, SESSION_ID)).isInstanceOf(InventoryException.class);

        verify(admissionIdGenerator, times(2)).generate();
        verify(flashAdmissionApi, times(2)).reserve(any());
    }

    @Test
    void onlyAdmittedResultEntersMySqlAndOtherResultsMapExactly() {
        List<ResultExpectation> expectations = List.of(
                new ResultExpectation(FlashAdmissionResult.IDEMPOTENT_PENDING, FlashException.class,
                        FlashErrorCode.FLASH_SERVICE_UNAVAILABLE),
                new ResultExpectation(FlashAdmissionResult.ALREADY_PENDING, FlashException.class,
                        FlashErrorCode.FLASH_SERVICE_UNAVAILABLE),
                new ResultExpectation(FlashAdmissionResult.ALREADY_ADMITTED, FlashException.class,
                        FlashErrorCode.FLASH_ALREADY_ADMITTED),
                new ResultExpectation(FlashAdmissionResult.SOLD_OUT, InventoryException.class,
                        InventoryErrorCode.SOLD_OUT),
                new ResultExpectation(FlashAdmissionResult.NOT_STARTED, OrderException.class,
                        OrderErrorCode.SALE_NOT_STARTED),
                new ResultExpectation(FlashAdmissionResult.ENDED, OrderException.class,
                        OrderErrorCode.SALE_ENDED),
                new ResultExpectation(FlashAdmissionResult.NOT_PREPARED, FlashException.class,
                        FlashErrorCode.FLASH_SERVICE_UNAVAILABLE),
                new ResultExpectation(FlashAdmissionResult.STATE_CONFLICT, FlashException.class,
                        FlashErrorCode.FLASH_SERVICE_UNAVAILABLE));

        for (ResultExpectation expectation : expectations) {
            reset(flashAdmissionApi, reservationApi);
            when(flashAdmissionApi.reserve(reserveCommand())).thenReturn(expectation.result());

            Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(
                    () -> service.reserve(USER_ID, SESSION_ID));

            assertThat(thrown).isInstanceOf(expectation.exceptionType());
            assertErrorCode(thrown, expectation.errorCode());
            verifyNoInteractions(reservationApi);
            verify(flashAdmissionApi, never()).releasePending(any());
            verify(flashAdmissionApi, never()).activate(any());
        }
    }

    @Test
    void admissionInfrastructureFailureNeverEntersMySqlOrCompensates() {
        when(flashAdmissionApi.reserve(reserveCommand()))
                .thenThrow(new FlashAdmissionUnavailableException("synthetic"));

        assertUnavailable(() -> service.reserve(USER_ID, SESSION_ID));

        verifyNoInteractions(reservationApi);
        verify(flashAdmissionApi, never()).releasePending(any());
        verify(flashAdmissionApi, never()).activate(any());
    }

    @Test
    void deterministicDomainFailuresReleaseExactlyOnceAndPreserveOriginalError() {
        List<RuntimeException> failures = List.of(
                new EventException(EventErrorCode.SESSION_NOT_FOUND),
                new OrderException(OrderErrorCode.RESERVATION_MODE_MISMATCH),
                new OrderException(OrderErrorCode.SALE_NOT_STARTED),
                new OrderException(OrderErrorCode.SALE_ENDED),
                new OrderException(OrderErrorCode.ACTIVE_PURCHASE_EXISTS),
                new InventoryException(InventoryErrorCode.INVENTORY_NOT_CONFIGURED),
                new InventoryException(InventoryErrorCode.SOLD_OUT));

        for (RuntimeException failure : failures) {
            reset(flashAdmissionApi, reservationApi);
            when(flashAdmissionApi.reserve(reserveCommand())).thenReturn(FlashAdmissionResult.ADMITTED);
            when(reservationApi.reserveFlash(USER_ID, SESSION_ID)).thenThrow(failure);
            when(flashAdmissionApi.releasePending(releaseCommand()))
                    .thenReturn(FlashReservationSettleResult.RELEASED);

            assertThatThrownBy(() -> service.reserve(USER_ID, SESSION_ID)).isSameAs(failure);
            verify(flashAdmissionApi, times(1)).releasePending(releaseCommand());
            verify(flashAdmissionApi, never()).activate(any());
        }
    }

    @Test
    void idempotentReleaseAlsoPreservesOriginalDeterministicError() {
        OrderException failure = new OrderException(OrderErrorCode.SALE_ENDED);
        givenAdmittedReservationFailure(failure);
        when(flashAdmissionApi.releasePending(releaseCommand()))
                .thenReturn(FlashReservationSettleResult.IDEMPOTENT_RELEASED);

        assertThatThrownBy(() -> service.reserve(USER_ID, SESSION_ID)).isSameAs(failure);
    }

    @Test
    void compensationFailureOverridesOriginalDomainErrorWithServiceUnavailable() {
        for (FlashReservationSettleResult result : List.of(
                FlashReservationSettleResult.FENCE_CONFLICT,
                FlashReservationSettleResult.STATE_CONFLICT,
                FlashReservationSettleResult.NOT_PREPARED)) {
            reset(flashAdmissionApi, reservationApi);
            givenAdmittedReservationFailure(new OrderException(OrderErrorCode.SALE_ENDED));
            when(flashAdmissionApi.releasePending(releaseCommand())).thenReturn(result);

            assertUnavailable(() -> service.reserve(USER_ID, SESSION_ID));
        }

        reset(flashAdmissionApi, reservationApi);
        givenAdmittedReservationFailure(new OrderException(OrderErrorCode.SALE_ENDED));
        when(flashAdmissionApi.releasePending(releaseCommand()))
                .thenThrow(new FlashAdmissionUnavailableException("synthetic"));
        assertUnavailable(() -> service.reserve(USER_ID, SESSION_ID));
    }

    @Test
    void transactionNotStartedAlwaysCompensatesAndReturnsUnavailable() {
        for (FlashReservationSettleResult result : List.of(
                FlashReservationSettleResult.RELEASED,
                FlashReservationSettleResult.IDEMPOTENT_RELEASED,
                FlashReservationSettleResult.FENCE_CONFLICT)) {
            reset(flashAdmissionApi, reservationApi);
            givenAdmittedReservationFailure(new CannotCreateTransactionException("synthetic"));
            when(flashAdmissionApi.releasePending(releaseCommand())).thenReturn(result);

            assertUnavailable(() -> service.reserve(USER_ID, SESSION_ID));
            verify(flashAdmissionApi).releasePending(releaseCommand());
        }

        reset(flashAdmissionApi, reservationApi);
        givenAdmittedReservationFailure(new CannotCreateTransactionException("synthetic"));
        when(flashAdmissionApi.releasePending(releaseCommand()))
                .thenThrow(new FlashAdmissionUnavailableException("synthetic"));
        assertUnavailable(() -> service.reserve(USER_ID, SESSION_ID));
    }

    @Test
    void ambiguousDatabaseOutcomesNeverCompensate() {
        List<RuntimeException> failures = List.of(
                new DataAccessResourceFailureException("synthetic"),
                new TransactionSystemException("synthetic"),
                new IllegalStateException("synthetic"));

        for (RuntimeException failure : failures) {
            reset(flashAdmissionApi, reservationApi);
            givenAdmittedReservationFailure(failure);

            assertUnavailable(() -> service.reserve(USER_ID, SESSION_ID));
            verify(flashAdmissionApi, never()).releasePending(any());
            verify(flashAdmissionApi, never()).activate(any());
        }
    }

    @Test
    void activateSuccessAndExpectedFailuresNeverChangeCommittedResult() {
        for (FlashReservationSettleResult result : List.of(
                FlashReservationSettleResult.ACTIVATED,
                FlashReservationSettleResult.IDEMPOTENT_ACTIVATED,
                FlashReservationSettleResult.FENCE_CONFLICT,
                FlashReservationSettleResult.STATE_CONFLICT,
                FlashReservationSettleResult.NOT_PREPARED)) {
            reset(flashAdmissionApi, reservationApi);
            OrderView order = order();
            when(flashAdmissionApi.reserve(reserveCommand())).thenReturn(FlashAdmissionResult.ADMITTED);
            when(reservationApi.reserveFlash(USER_ID, SESSION_ID)).thenReturn(order);
            when(flashAdmissionApi.activate(activateCommand(order.id()))).thenReturn(result);

            assertThat(service.reserve(USER_ID, SESSION_ID)).isEqualTo(order);
            verify(flashAdmissionApi, never()).releasePending(any());
        }

        reset(flashAdmissionApi, reservationApi);
        OrderView order = order();
        when(flashAdmissionApi.reserve(reserveCommand())).thenReturn(FlashAdmissionResult.ADMITTED);
        when(reservationApi.reserveFlash(USER_ID, SESSION_ID)).thenReturn(order);
        when(flashAdmissionApi.activate(activateCommand(order.id())))
                .thenThrow(new FlashAdmissionUnavailableException("synthetic"));

        assertThat(service.reserve(USER_ID, SESSION_ID)).isEqualTo(order);
        verify(flashAdmissionApi, never()).releasePending(any());
    }

    private void givenAdmittedReservationFailure(RuntimeException failure) {
        when(flashAdmissionApi.reserve(reserveCommand())).thenReturn(FlashAdmissionResult.ADMITTED);
        when(reservationApi.reserveFlash(USER_ID, SESSION_ID)).thenThrow(failure);
    }

    private static void assertUnavailable(ThrowingCall call) {
        assertThatThrownBy(call::run)
                .isInstanceOf(FlashException.class)
                .extracting(exception -> ((FlashException) exception).getErrorCode())
                .isEqualTo(FlashErrorCode.FLASH_SERVICE_UNAVAILABLE);
    }

    private static void assertErrorCode(Throwable thrown, Object expected) {
        Object actual = null;
        if (thrown instanceof FlashException exception) {
            actual = exception.getErrorCode();
        } else if (thrown instanceof InventoryException exception) {
            actual = exception.getErrorCode();
        } else if (thrown instanceof OrderException exception) {
            actual = exception.getErrorCode();
        }
        assertThat(actual).isEqualTo(expected);
    }

    private static ReserveFlashAdmissionCommand reserveCommand() {
        return new ReserveFlashAdmissionCommand(SESSION_ID, USER_ID, ADMISSION_ID);
    }

    private static ReleasePendingFlashAdmissionCommand releaseCommand() {
        return new ReleasePendingFlashAdmissionCommand(SESSION_ID, USER_ID, ADMISSION_ID);
    }

    private static ActivateFlashAdmissionCommand activateCommand(long orderId) {
        return new ActivateFlashAdmissionCommand(SESSION_ID, USER_ID, ADMISSION_ID, orderId);
    }

    private static OrderView order() {
        LocalDateTime now = LocalDateTime.of(2030, 1, 1, 0, 0);
        return new OrderView(91L, "EH-91", SESSION_ID, OrderStatus.WAIT_PAY,
                now.plusMinutes(15), now, now);
    }

    @FunctionalInterface
    private interface ThrowingCall {
        void run();
    }

    private record ResultExpectation(
            FlashAdmissionResult result,
            Class<? extends RuntimeException> exceptionType,
            Object errorCode) {
    }
}

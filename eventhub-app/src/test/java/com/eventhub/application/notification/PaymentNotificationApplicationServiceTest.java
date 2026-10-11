package com.eventhub.application.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;

import com.eventhub.notification.api.NotificationEventPublisherApi;
import com.eventhub.notification.api.error.NotificationPublishException;
import com.eventhub.notification.api.event.TicketIssuedEvent;
import com.eventhub.order.api.OrderCommandApi;
import com.eventhub.order.api.model.OrderStatus;
import com.eventhub.order.api.model.OrderView;
import com.eventhub.order.api.model.PaymentResult;
import com.eventhub.ticket.api.model.TicketStatus;
import com.eventhub.ticket.api.model.TicketView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.TransactionSystemException;

class PaymentNotificationApplicationServiceTest {

    private static final long USER_ID = 101L;
    private static final long ORDER_ID = 202L;
    private static final long SESSION_ID = 303L;
    private static final Instant OCCURRED_AT = Instant.parse("2037-08-09T10:11:12.123456Z");

    private final OrderCommandApi orderCommandApi = mock(OrderCommandApi.class);
    private final NotificationEventPublisherApi publisher = mock(NotificationEventPublisherApi.class);
    private final Clock clock = mock(Clock.class);

    private PaymentNotificationApplicationService service;

    @BeforeEach
    void setUp() {
        service = new PaymentNotificationApplicationService(orderCommandApi, publisher, clock);
    }

    @Test
    void paysThenPublishesTicketIssuedEventAndReturnsTheExactResult() {
        PaymentResult paymentResult = paymentResult();
        when(orderCommandApi.pay(USER_ID, ORDER_ID)).thenReturn(paymentResult);
        when(clock.instant()).thenReturn(OCCURRED_AT);

        PaymentResult actual = service.pay(USER_ID, ORDER_ID);

        assertThat(actual).isSameAs(paymentResult);
        verify(orderCommandApi).pay(USER_ID, ORDER_ID);
        verify(clock).instant();
        ArgumentCaptor<TicketIssuedEvent> eventCaptor = ArgumentCaptor.forClass(TicketIssuedEvent.class);
        verify(publisher).publishTicketIssued(eventCaptor.capture());
        TicketIssuedEvent event = eventCaptor.getValue();
        assertThat(event.eventId()).isNotNull();
        assertThat(event.schemaVersion()).isEqualTo(1);
        assertThat(event.userId()).isEqualTo(USER_ID);
        assertThat(event.orderId()).isEqualTo(ORDER_ID);
        assertThat(event.sessionId()).isEqualTo(SESSION_ID);
        assertThat(event.ticketNo()).isEqualTo(paymentResult.ticket().ticketNo());
        assertThat(event.occurredAt()).isEqualTo(OCCURRED_AT);
    }

    @Test
    void propagatesPayFailureWithoutReadingNotificationClockOrPublishing() {
        RuntimeException failure = new RuntimeException("synthetic pay failure");
        when(orderCommandApi.pay(USER_ID, ORDER_ID)).thenThrow(failure);

        assertThatThrownBy(() -> service.pay(USER_ID, ORDER_ID)).isSameAs(failure);

        verify(orderCommandApi).pay(USER_ID, ORDER_ID);
        verifyNoInteractions(clock, publisher);
    }

    @Test
    void propagatesCommitFailureWithoutEnteringNotificationStage() {
        TransactionSystemException failure = new TransactionSystemException("synthetic commit failure");
        when(orderCommandApi.pay(USER_ID, ORDER_ID)).thenThrow(failure);

        assertThatThrownBy(() -> service.pay(USER_ID, ORDER_ID)).isSameAs(failure);

        verify(orderCommandApi).pay(USER_ID, ORDER_ID);
        verifyNoInteractions(clock, publisher);
    }

    @Test
    void preservesCommittedPayResultWhenEventConstructionStageFails() {
        PaymentResult paymentResult = paymentResult();
        when(orderCommandApi.pay(USER_ID, ORDER_ID)).thenReturn(paymentResult);
        when(clock.instant()).thenThrow(new IllegalStateException("synthetic clock failure"));

        assertThat(service.pay(USER_ID, ORDER_ID)).isSameAs(paymentResult);

        verify(orderCommandApi, times(1)).pay(USER_ID, ORDER_ID);
        verify(clock).instant();
        verifyNoInteractions(publisher);
    }

    @Test
    void preservesCommittedPayResultWhenPublisherReportsNotificationFailure() {
        PaymentResult paymentResult = paymentResult();
        when(orderCommandApi.pay(USER_ID, ORDER_ID)).thenReturn(paymentResult);
        when(clock.instant()).thenReturn(OCCURRED_AT);
        NotificationPublishException failure = new NotificationPublishException(
                new IllegalStateException("synthetic broker failure"));
        doThrow(failure).when(publisher).publishTicketIssued(any());

        assertThat(service.pay(USER_ID, ORDER_ID)).isSameAs(paymentResult);

        verify(orderCommandApi).pay(USER_ID, ORDER_ID);
        verify(publisher).publishTicketIssued(any());
    }

    @Test
    void preservesCommittedPayResultForUnexpectedNotificationRuntimeFailure() {
        PaymentResult paymentResult = paymentResult();
        when(orderCommandApi.pay(USER_ID, ORDER_ID)).thenReturn(paymentResult);
        when(clock.instant()).thenReturn(OCCURRED_AT);
        doThrow(new IllegalArgumentException("synthetic unexpected failure"))
                .when(publisher).publishTicketIssued(any());

        assertThat(service.pay(USER_ID, ORDER_ID)).isSameAs(paymentResult);

        verify(orderCommandApi, times(1)).pay(USER_ID, ORDER_ID);
        verify(publisher).publishTicketIssued(any());
    }

    private static PaymentResult paymentResult() {
        LocalDateTime timestamp = LocalDateTime.of(2037, 8, 9, 10, 11, 12, 123_456_000);
        OrderView order = new OrderView(
                ORDER_ID,
                "P11B3-ORDER",
                SESSION_ID,
                OrderStatus.PAID,
                timestamp.plusMinutes(5),
                timestamp.minusMinutes(10),
                timestamp);
        TicketView ticket = new TicketView(
                "TKT-0123456789ABCDEF0123456789ABCDEF",
                ORDER_ID,
                SESSION_ID,
                TicketStatus.VALID,
                timestamp);
        return new PaymentResult(order, ticket);
    }
}

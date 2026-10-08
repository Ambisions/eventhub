package com.eventhub.application.flash;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import com.eventhub.application.flash.error.FlashErrorCode;
import com.eventhub.application.flash.error.FlashException;
import com.eventhub.event.api.FlashPreparationEventQueryApi;
import com.eventhub.event.api.error.EventErrorCode;
import com.eventhub.event.api.error.EventException;
import com.eventhub.event.api.model.EventStatus;
import com.eventhub.event.api.model.FlashPreparationEventContext;
import com.eventhub.event.api.model.SessionSaleMode;
import com.eventhub.inventory.api.FlashAdmissionApi;
import com.eventhub.inventory.api.InventoryQueryApi;
import com.eventhub.inventory.api.command.PrepareFlashAdmissionCommand;
import com.eventhub.inventory.api.error.FlashAdmissionUnavailableException;
import com.eventhub.inventory.api.error.InventoryErrorCode;
import com.eventhub.inventory.api.error.InventoryException;
import com.eventhub.inventory.api.model.FlashPrepareResult;
import com.eventhub.inventory.api.model.InventoryView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FlashPreparationApplicationServiceTest {

    private static final long EVENT_ID = 11L;
    private static final long SESSION_ID = 22L;
    private static final Instant NOW = Instant.parse("2030-01-01T00:00:00.123456789Z");
    private static final LocalDateTime SALE_START = LocalDateTime.ofInstant(
            Instant.parse("2030-01-01T00:01:00Z"), ZoneOffset.UTC);
    private static final LocalDateTime SALE_END = SALE_START.plusHours(1);

    @Mock
    private FlashPreparationEventQueryApi eventQueryApi;

    @Mock
    private InventoryQueryApi inventoryQueryApi;

    @Mock
    private FlashAdmissionApi flashAdmissionApi;

    @Mock
    private Clock clock;

    private FlashPreparationApplicationService service;

    @BeforeEach
    void setUp() {
        service = new FlashPreparationApplicationService(
                eventQueryApi, inventoryQueryApi, flashAdmissionApi, clock);
    }

    @Test
    void orchestratesInFrozenOrderWithOneUtcMicrosecondClockSnapshot() {
        givenEligible();
        when(clock.instant()).thenReturn(NOW);
        when(flashAdmissionApi.prepare(org.mockito.ArgumentMatchers.any()))
                .thenReturn(FlashPrepareResult.PREPARED);

        service.prepare(EVENT_ID, SESSION_ID);

        InOrder order = inOrder(eventQueryApi, inventoryQueryApi, clock, flashAdmissionApi);
        order.verify(eventQueryApi).getPreparationContext(EVENT_ID, SESSION_ID);
        order.verify(inventoryQueryApi).getInventory(SESSION_ID);
        order.verify(clock).instant();
        ArgumentCaptor<PrepareFlashAdmissionCommand> command =
                ArgumentCaptor.forClass(PrepareFlashAdmissionCommand.class);
        order.verify(flashAdmissionApi).prepare(command.capture());
        assertEquals(SESSION_ID, command.getValue().sessionId());
        assertEquals(5, command.getValue().capacity());
        assertEquals(1_893_456_060_000_000L, command.getValue().saleStartUs());
        assertEquals(1_893_459_660_000_000L, command.getValue().saleEndUs());
        verify(clock, times(1)).instant();
    }

    @Test
    void rejectsEventEligibilityBeforeInventoryAndRedis() {
        when(eventQueryApi.getPreparationContext(EVENT_ID, SESSION_ID))
                .thenReturn(context(EventStatus.DRAFT, SessionSaleMode.FLASH));

        assertFailure(FlashErrorCode.FLASH_PREPARATION_NOT_ALLOWED);

        verifyNoInteractions(inventoryQueryApi, flashAdmissionApi, clock);
    }

    @Test
    void mapsMissingResourcesWithoutCallingRedis() {
        when(eventQueryApi.getPreparationContext(EVENT_ID, SESSION_ID))
                .thenThrow(new EventException(EventErrorCode.SESSION_NOT_FOUND));
        assertFailure(FlashErrorCode.FLASH_PREPARATION_NOT_FOUND);
        verifyNoInteractions(inventoryQueryApi, flashAdmissionApi, clock);

        org.mockito.Mockito.reset(eventQueryApi, inventoryQueryApi, flashAdmissionApi, clock);
        when(eventQueryApi.getPreparationContext(EVENT_ID, SESSION_ID))
                .thenReturn(context(EventStatus.PUBLISHED, SessionSaleMode.FLASH));
        when(inventoryQueryApi.getInventory(SESSION_ID))
                .thenThrow(new InventoryException(InventoryErrorCode.INVENTORY_NOT_CONFIGURED));
        assertFailure(FlashErrorCode.FLASH_PREPARATION_NOT_FOUND);
        verifyNoInteractions(flashAdmissionApi, clock);
    }

    @Test
    void rejectsDirtyInventoryAndExactSaleStartWithoutRedisMutation() {
        when(eventQueryApi.getPreparationContext(EVENT_ID, SESSION_ID))
                .thenReturn(context(EventStatus.PUBLISHED, SessionSaleMode.FLASH));
        when(inventoryQueryApi.getInventory(SESSION_ID))
                .thenReturn(new InventoryView(SESSION_ID, 5, 4, 1, 0, null));
        assertFailure(FlashErrorCode.FLASH_PREPARATION_NOT_ALLOWED);
        verifyNoInteractions(flashAdmissionApi, clock);

        org.mockito.Mockito.reset(inventoryQueryApi, flashAdmissionApi, clock);
        when(inventoryQueryApi.getInventory(SESSION_ID)).thenReturn(cleanInventory());
        when(clock.instant()).thenReturn(SALE_START.toInstant(ZoneOffset.UTC));
        assertFailure(FlashErrorCode.FLASH_PREPARATION_WINDOW_CLOSED);
        verify(flashAdmissionApi, never()).prepare(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void mapsRedisResultsAndInfrastructureFailure() {
        givenEligible();
        when(clock.instant()).thenReturn(NOW);
        when(flashAdmissionApi.prepare(org.mockito.ArgumentMatchers.any()))
                .thenReturn(FlashPrepareResult.IDEMPOTENT_PREPARED)
                .thenReturn(FlashPrepareResult.WINDOW_CLOSED)
                .thenReturn(FlashPrepareResult.STATE_CONFLICT)
                .thenThrow(new FlashAdmissionUnavailableException("synthetic"));

        service.prepare(EVENT_ID, SESSION_ID);
        assertFailure(FlashErrorCode.FLASH_PREPARATION_WINDOW_CLOSED);
        assertFailure(FlashErrorCode.FLASH_PREPARATION_STATE_CONFLICT);
        assertFailure(FlashErrorCode.FLASH_SERVICE_UNAVAILABLE);
    }

    private void givenEligible() {
        when(eventQueryApi.getPreparationContext(EVENT_ID, SESSION_ID))
                .thenReturn(context(EventStatus.PUBLISHED, SessionSaleMode.FLASH));
        when(inventoryQueryApi.getInventory(SESSION_ID)).thenReturn(cleanInventory());
    }

    private static FlashPreparationEventContext context(
            EventStatus eventStatus, SessionSaleMode saleMode) {
        return new FlashPreparationEventContext(
                EVENT_ID, SESSION_ID, eventStatus, saleMode, SALE_START, SALE_END);
    }

    private static InventoryView cleanInventory() {
        return new InventoryView(SESSION_ID, 5, 5, 0, 0, null);
    }

    private void assertFailure(FlashErrorCode expected) {
        FlashException exception = assertThrows(
                FlashException.class,
                () -> service.prepare(EVENT_ID, SESSION_ID));
        assertEquals(expected, exception.getErrorCode());
    }
}

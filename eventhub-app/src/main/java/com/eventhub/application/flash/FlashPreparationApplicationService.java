package com.eventhub.application.flash;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

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
import org.springframework.stereotype.Service;

@Service
public final class FlashPreparationApplicationService {

    private final FlashPreparationEventQueryApi eventQueryApi;
    private final InventoryQueryApi inventoryQueryApi;
    private final FlashAdmissionApi flashAdmissionApi;
    private final Clock clock;

    public FlashPreparationApplicationService(
            FlashPreparationEventQueryApi eventQueryApi,
            InventoryQueryApi inventoryQueryApi,
            FlashAdmissionApi flashAdmissionApi,
            Clock clock) {
        this.eventQueryApi = eventQueryApi;
        this.inventoryQueryApi = inventoryQueryApi;
        this.flashAdmissionApi = flashAdmissionApi;
        this.clock = clock;
    }

    public void prepare(long eventId, long sessionId) {
        FlashPreparationEventContext eventContext = getEventContext(eventId, sessionId);
        requirePublishedFlashSession(eventContext);

        InventoryView inventory = getInventory(sessionId);
        requireCleanInventory(inventory);

        LocalDateTime prepareTime = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
                .truncatedTo(ChronoUnit.MICROS);
        if (!prepareTime.isBefore(eventContext.saleStartAt())) {
            throw failure(FlashErrorCode.FLASH_PREPARATION_WINDOW_CLOSED);
        }

        PrepareFlashAdmissionCommand command = new PrepareFlashAdmissionCommand(
                sessionId,
                inventory.totalStock(),
                toEpochMicros(eventContext.saleStartAt()),
                toEpochMicros(eventContext.saleEndAt()));
        final FlashPrepareResult result;
        try {
            result = flashAdmissionApi.prepare(command);
        } catch (FlashAdmissionUnavailableException exception) {
            throw new FlashException(FlashErrorCode.FLASH_SERVICE_UNAVAILABLE, exception);
        }
        switch (result) {
            case PREPARED, IDEMPOTENT_PREPARED -> {
                return;
            }
            case WINDOW_CLOSED -> throw failure(
                    FlashErrorCode.FLASH_PREPARATION_WINDOW_CLOSED);
            case STATE_CONFLICT -> throw failure(
                    FlashErrorCode.FLASH_PREPARATION_STATE_CONFLICT);
        }
    }

    private FlashPreparationEventContext getEventContext(long eventId, long sessionId) {
        try {
            return eventQueryApi.getPreparationContext(eventId, sessionId);
        } catch (EventException exception) {
            if (exception.getErrorCode() == EventErrorCode.EVENT_NOT_FOUND
                    || exception.getErrorCode() == EventErrorCode.SESSION_NOT_FOUND) {
                throw new FlashException(FlashErrorCode.FLASH_PREPARATION_NOT_FOUND, exception);
            }
            throw exception;
        }
    }

    private InventoryView getInventory(long sessionId) {
        try {
            return inventoryQueryApi.getInventory(sessionId);
        } catch (InventoryException exception) {
            if (exception.getErrorCode() == InventoryErrorCode.INVENTORY_NOT_CONFIGURED) {
                throw new FlashException(FlashErrorCode.FLASH_PREPARATION_NOT_FOUND, exception);
            }
            throw exception;
        }
    }

    private static void requirePublishedFlashSession(FlashPreparationEventContext context) {
        if (context.eventStatus() != EventStatus.PUBLISHED
                || context.saleMode() != SessionSaleMode.FLASH) {
            throw failure(FlashErrorCode.FLASH_PREPARATION_NOT_ALLOWED);
        }
    }

    private static void requireCleanInventory(InventoryView inventory) {
        if (inventory.lockedStock() != 0
                || inventory.soldStock() != 0
                || inventory.totalStock() != inventory.availableStock()) {
            throw failure(FlashErrorCode.FLASH_PREPARATION_NOT_ALLOWED);
        }
    }

    private static long toEpochMicros(LocalDateTime dateTime) {
        Instant instant = dateTime.truncatedTo(ChronoUnit.MICROS).toInstant(ZoneOffset.UTC);
        return Math.addExact(
                Math.multiplyExact(instant.getEpochSecond(), 1_000_000L),
                instant.getNano() / 1_000L);
    }

    private static FlashException failure(FlashErrorCode errorCode) {
        return new FlashException(errorCode);
    }
}

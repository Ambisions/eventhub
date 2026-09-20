package com.eventhub.inventory.service;

import com.eventhub.event.api.OrganizerEventSessionGuardApi;
import com.eventhub.inventory.api.command.ConfigureInventoryCommand;
import com.eventhub.inventory.api.error.InventoryErrorCode;
import com.eventhub.inventory.api.error.InventoryException;
import com.eventhub.inventory.mapper.InventoryMapper;
import com.eventhub.inventory.repository.InventoryEntity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InventoryServiceTest {

    private static final long OWNER_ID = 10L;
    private static final long SESSION_ID = 20L;

    @Mock
    private OrganizerEventSessionGuardApi eventSessionGuardApi;

    @Mock
    private InventoryMapper inventoryMapper;

    @InjectMocks
    private InventoryService inventoryService;

    @Test
    void rejectsNegativeInventoryBeforeGuardOrMutation() {
        InventoryException exception = assertThrows(InventoryException.class,
                () -> inventoryService.configureInventory(
                        OWNER_ID, new ConfigureInventoryCommand(SESSION_ID, -1)));

        assertEquals(InventoryErrorCode.INVALID_INVENTORY_TOTAL, exception.getErrorCode());
        verify(eventSessionGuardApi, never()).requireEditableOwnedSession(OWNER_ID, SESSION_ID);
    }

    @Test
    void configuresInitialInventoryAfterEventGuard() {
        InventoryEntity persisted = inventory(7, 7, 0, 0);
        when(inventoryMapper.selectBySessionId(SESSION_ID)).thenReturn(null, persisted);

        var result = inventoryService.configureInventory(
                OWNER_ID, new ConfigureInventoryCommand(SESSION_ID, 7));

        verify(eventSessionGuardApi).requireEditableOwnedSession(OWNER_ID, SESSION_ID);
        ArgumentCaptor<InventoryEntity> captor = ArgumentCaptor.forClass(InventoryEntity.class);
        verify(inventoryMapper).insert(captor.capture());
        assertEquals(7, captor.getValue().getTotalStock());
        assertEquals(7, captor.getValue().getAvailableStock());
        assertEquals(0, captor.getValue().getLockedStock());
        assertEquals(0, captor.getValue().getSoldStock());
        assertEquals(7, result.totalStock());
        assertEquals(7, result.availableStock());
    }

    @Test
    void reconfiguresUnusedInventoryWithConditionalUpdate() {
        InventoryEntity current = inventory(5, 5, 0, 0);
        InventoryEntity updated = inventory(9, 9, 0, 0);
        when(inventoryMapper.selectBySessionId(SESSION_ID)).thenReturn(current, updated);
        when(inventoryMapper.updateAvailableCapacity(SESSION_ID, 9)).thenReturn(1);

        var result = inventoryService.configureInventory(
                OWNER_ID, new ConfigureInventoryCommand(SESSION_ID, 9));

        assertEquals(9, result.totalStock());
        verify(inventoryMapper).updateAvailableCapacity(SESSION_ID, 9);
    }

    @Test
    void rejectsCapacityChangeAfterInventoryWasLocked() {
        InventoryEntity inUse = inventory(5, 4, 1, 0);
        when(inventoryMapper.selectBySessionId(SESSION_ID)).thenReturn(inUse, inUse);
        when(inventoryMapper.updateAvailableCapacity(SESSION_ID, 8)).thenReturn(0);

        InventoryException exception = assertThrows(InventoryException.class,
                () -> inventoryService.configureInventory(
                        OWNER_ID, new ConfigureInventoryCommand(SESSION_ID, 8)));

        assertEquals(InventoryErrorCode.INVENTORY_ALREADY_IN_USE, exception.getErrorCode());
    }

    @Test
    void rejectsCapacityChangeAfterInventoryWasSold() {
        InventoryEntity inUse = inventory(5, 4, 0, 1);
        when(inventoryMapper.selectBySessionId(SESSION_ID)).thenReturn(inUse, inUse);
        when(inventoryMapper.updateAvailableCapacity(SESSION_ID, 8)).thenReturn(0);

        InventoryException exception = assertThrows(InventoryException.class,
                () -> inventoryService.configureInventory(
                        OWNER_ID, new ConfigureInventoryCommand(SESSION_ID, 8)));

        assertEquals(InventoryErrorCode.INVENTORY_ALREADY_IN_USE, exception.getErrorCode());
    }

    @Test
    void treatsSameValueZeroAffectedRowsAsIdempotentSuccess() {
        InventoryEntity same = inventory(5, 5, 0, 0);
        when(inventoryMapper.selectBySessionId(SESSION_ID)).thenReturn(same, same, same);
        when(inventoryMapper.updateAvailableCapacity(SESSION_ID, 5)).thenReturn(0);

        var result = inventoryService.configureInventory(
                OWNER_ID, new ConfigureInventoryCommand(SESSION_ID, 5));

        assertEquals(5, result.totalStock());
        assertEquals(5, result.availableStock());
    }

    @Test
    void mapsReservationMutationFailuresToStableInventoryErrors() {
        when(inventoryMapper.lockOne(SESSION_ID)).thenReturn(0);
        when(inventoryMapper.selectBySessionId(SESSION_ID)).thenReturn(null);
        assertInventoryError(() -> inventoryService.lockOne(SESSION_ID),
                InventoryErrorCode.INVENTORY_NOT_CONFIGURED);

        when(inventoryMapper.selectBySessionId(SESSION_ID)).thenReturn(inventory(0, 0, 0, 0));
        assertInventoryError(() -> inventoryService.lockOne(SESSION_ID), InventoryErrorCode.SOLD_OUT);

        when(inventoryMapper.confirmSoldOne(SESSION_ID)).thenReturn(0);
        assertInventoryError(() -> inventoryService.confirmSoldOne(SESSION_ID),
                InventoryErrorCode.INVENTORY_STATE_CONFLICT);

        when(inventoryMapper.releaseOne(SESSION_ID)).thenReturn(0);
        assertInventoryError(() -> inventoryService.releaseOne(SESSION_ID),
                InventoryErrorCode.INVENTORY_STATE_CONFLICT);
    }

    private static InventoryEntity inventory(int total, int available, int locked, int sold) {
        InventoryEntity entity = new InventoryEntity();
        entity.setId(1L);
        entity.setSessionId(SESSION_ID);
        entity.setTotalStock(total);
        entity.setAvailableStock(available);
        entity.setLockedStock(locked);
        entity.setSoldStock(sold);
        return entity;
    }

    private static void assertInventoryError(Runnable action, InventoryErrorCode expected) {
        InventoryException exception = assertThrows(InventoryException.class, action::run);
        assertEquals(expected, exception.getErrorCode());
    }
}

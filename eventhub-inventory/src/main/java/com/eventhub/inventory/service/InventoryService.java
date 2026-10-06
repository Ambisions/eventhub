package com.eventhub.inventory.service;

import java.util.Objects;

import com.eventhub.event.api.OrganizerEventSessionGuardApi;
import com.eventhub.inventory.api.InventoryManagementApi;
import com.eventhub.inventory.api.InventoryQueryApi;
import com.eventhub.inventory.api.InventoryRefundApi;
import com.eventhub.inventory.api.InventoryReservationApi;
import com.eventhub.inventory.api.command.ConfigureInventoryCommand;
import com.eventhub.inventory.api.error.InventoryErrorCode;
import com.eventhub.inventory.api.error.InventoryException;
import com.eventhub.inventory.api.model.InventoryView;
import com.eventhub.inventory.mapper.InventoryMapper;
import com.eventhub.inventory.repository.InventoryEntity;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InventoryService implements InventoryManagementApi, InventoryQueryApi,
        InventoryReservationApi, InventoryRefundApi {

    private static final String SESSION_UNIQUE_CONSTRAINT = "uk_event_inventory_session";

    private final OrganizerEventSessionGuardApi eventSessionGuardApi;
    private final InventoryMapper inventoryMapper;

    public InventoryService(
            OrganizerEventSessionGuardApi eventSessionGuardApi,
            InventoryMapper inventoryMapper) {
        this.eventSessionGuardApi = eventSessionGuardApi;
        this.inventoryMapper = inventoryMapper;
    }

    @Override
    @Transactional
    public InventoryView configureInventory(long organizerUserId, ConfigureInventoryCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        if (command.totalStock() < 0) {
            throw new InventoryException(InventoryErrorCode.INVALID_INVENTORY_TOTAL);
        }

        eventSessionGuardApi.requireEditableOwnedSession(organizerUserId, command.sessionId());
        InventoryEntity current = inventoryMapper.selectBySessionId(command.sessionId());
        if (current == null) {
            insertInitialInventory(command);
        } else {
            updateUnusedInventory(command);
        }
        return toView(requireInventory(command.sessionId()));
    }

    @Override
    public InventoryView getInventory(long sessionId) {
        if (sessionId <= 0) {
            throw new InventoryException(InventoryErrorCode.INVENTORY_NOT_CONFIGURED);
        }
        InventoryEntity inventory = inventoryMapper.selectBySessionId(sessionId);
        if (inventory == null) {
            throw new InventoryException(InventoryErrorCode.INVENTORY_NOT_CONFIGURED);
        }
        return toView(inventory);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockOne(long sessionId) {
        if (inventoryMapper.lockOne(sessionId) == 1) {
            return;
        }
        InventoryEntity current = inventoryMapper.selectBySessionId(sessionId);
        if (current == null) {
            throw new InventoryException(InventoryErrorCode.INVENTORY_NOT_CONFIGURED);
        }
        throw new InventoryException(InventoryErrorCode.SOLD_OUT);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void confirmSoldOne(long sessionId) {
        if (inventoryMapper.confirmSoldOne(sessionId) != 1) {
            throw new InventoryException(InventoryErrorCode.INVENTORY_STATE_CONFLICT);
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void releaseOne(long sessionId) {
        if (inventoryMapper.releaseOne(sessionId) != 1) {
            throw new InventoryException(InventoryErrorCode.INVENTORY_STATE_CONFLICT);
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void refundSoldOne(long sessionId) {
        if (inventoryMapper.refundSoldOne(sessionId) != 1) {
            throw new InventoryException(InventoryErrorCode.INVENTORY_STATE_CONFLICT);
        }
    }

    private void insertInitialInventory(ConfigureInventoryCommand command) {
        InventoryEntity inventory = new InventoryEntity();
        inventory.setSessionId(command.sessionId());
        inventory.setTotalStock(command.totalStock());
        inventory.setAvailableStock(command.totalStock());
        inventory.setLockedStock(0);
        inventory.setSoldStock(0);
        try {
            inventoryMapper.insert(inventory);
        } catch (DuplicateKeyException exception) {
            if (!isSessionUniqueViolation(exception)) {
                throw exception;
            }
            classifyExistingConfiguration(command);
        }
    }

    private void updateUnusedInventory(ConfigureInventoryCommand command) {
        if (inventoryMapper.updateAvailableCapacity(command.sessionId(), command.totalStock()) == 0) {
            classifyExistingConfiguration(command);
        }
    }

    private void classifyExistingConfiguration(ConfigureInventoryCommand command) {
        InventoryEntity current = inventoryMapper.selectBySessionId(command.sessionId());
        if (current == null) {
            throw new InventoryException(InventoryErrorCode.INVENTORY_STATE_CONFLICT);
        }
        if (current.getLockedStock() == 0 && current.getSoldStock() == 0
                && current.getTotalStock() == command.totalStock()
                && current.getAvailableStock() == command.totalStock()) {
            return;
        }
        if (current.getLockedStock() > 0 || current.getSoldStock() > 0) {
            throw new InventoryException(InventoryErrorCode.INVENTORY_ALREADY_IN_USE);
        }
        throw new InventoryException(InventoryErrorCode.INVENTORY_STATE_CONFLICT);
    }

    private InventoryEntity requireInventory(long sessionId) {
        InventoryEntity inventory = inventoryMapper.selectBySessionId(sessionId);
        if (inventory == null) {
            throw new InventoryException(InventoryErrorCode.INVENTORY_STATE_CONFLICT);
        }
        return inventory;
    }

    private static boolean isSessionUniqueViolation(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && message.contains(SESSION_UNIQUE_CONSTRAINT)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static InventoryView toView(InventoryEntity inventory) {
        return new InventoryView(
                inventory.getSessionId(),
                inventory.getTotalStock(),
                inventory.getAvailableStock(),
                inventory.getLockedStock(),
                inventory.getSoldStock(),
                inventory.getUpdatedAt());
    }
}

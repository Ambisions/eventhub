package com.eventhub.inventory.api;

import com.eventhub.inventory.api.model.InventoryView;

public interface InventoryQueryApi {

    InventoryView getInventory(long sessionId);
}

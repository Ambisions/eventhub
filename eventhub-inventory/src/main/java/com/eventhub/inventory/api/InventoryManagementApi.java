package com.eventhub.inventory.api;

import com.eventhub.inventory.api.command.ConfigureInventoryCommand;
import com.eventhub.inventory.api.model.InventoryView;

public interface InventoryManagementApi {

    InventoryView configureInventory(long organizerUserId, ConfigureInventoryCommand command);
}

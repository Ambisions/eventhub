package com.eventhub.controller.inventory;

import com.eventhub.controller.inventory.dto.ConfigureInventoryRequest;
import com.eventhub.controller.inventory.dto.InventoryResponse;
import com.eventhub.inventory.api.InventoryManagementApi;
import com.eventhub.inventory.api.command.ConfigureInventoryCommand;
import com.eventhub.security.EventHubAuthenticatedUser;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/organizer/sessions")
public final class OrganizerInventoryController {

    private final InventoryManagementApi inventoryManagementApi;

    public OrganizerInventoryController(InventoryManagementApi inventoryManagementApi) {
        this.inventoryManagementApi = inventoryManagementApi;
    }

    @PutMapping("/{sessionId}/inventory")
    public InventoryResponse configureInventory(
            @AuthenticationPrincipal EventHubAuthenticatedUser currentUser,
            @PathVariable long sessionId,
            @Valid @RequestBody ConfigureInventoryRequest request) {
        return InventoryResponse.from(inventoryManagementApi.configureInventory(
                currentUser.userId(), new ConfigureInventoryCommand(sessionId, request.totalStock())));
    }
}

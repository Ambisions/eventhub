package com.eventhub.inventory.api;

import com.eventhub.inventory.api.command.FlashOrderLifecycleCommand;
import com.eventhub.inventory.api.model.FlashOrderLifecycleResult;

public interface FlashOrderLifecycleApi {

    FlashOrderLifecycleResult releaseActive(FlashOrderLifecycleCommand command);

    FlashOrderLifecycleResult refundActive(FlashOrderLifecycleCommand command);
}

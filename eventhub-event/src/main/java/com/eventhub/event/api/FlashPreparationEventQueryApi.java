package com.eventhub.event.api;

import com.eventhub.event.api.model.FlashPreparationEventContext;

public interface FlashPreparationEventQueryApi {

    FlashPreparationEventContext getPreparationContext(long eventId, long sessionId);
}

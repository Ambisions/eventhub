package com.eventhub.controller.event;

import com.eventhub.application.flash.FlashPreparationApplicationService;
import com.eventhub.controller.event.dto.FlashPreparationResponse;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class FlashPreparationController {

    private final FlashPreparationApplicationService preparationService;

    public FlashPreparationController(FlashPreparationApplicationService preparationService) {
        this.preparationService = preparationService;
    }

    @PostMapping("/api/events/{eventId}/sessions/{sessionId}/flash/prepare")
    public FlashPreparationResponse prepare(
            @PathVariable long eventId,
            @PathVariable long sessionId) {
        preparationService.prepare(eventId, sessionId);
        return FlashPreparationResponse.prepared(sessionId);
    }
}

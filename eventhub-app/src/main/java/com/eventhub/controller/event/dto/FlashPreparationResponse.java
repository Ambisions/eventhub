package com.eventhub.controller.event.dto;

public record FlashPreparationResponse(long sessionId, String state) {

    public static FlashPreparationResponse prepared(long sessionId) {
        return new FlashPreparationResponse(sessionId, "PREPARED");
    }
}

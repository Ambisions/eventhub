package com.eventhub.inventory.api;

public interface InventoryReservationApi {

    void lockOne(long sessionId);

    void confirmSoldOne(long sessionId);

    void releaseOne(long sessionId);
}

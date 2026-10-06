package com.eventhub.inventory.api;

import com.eventhub.inventory.api.command.ActivateFlashAdmissionCommand;
import com.eventhub.inventory.api.command.PrepareFlashAdmissionCommand;
import com.eventhub.inventory.api.command.ReleasePendingFlashAdmissionCommand;
import com.eventhub.inventory.api.command.ReserveFlashAdmissionCommand;
import com.eventhub.inventory.api.model.FlashAdmissionResult;
import com.eventhub.inventory.api.model.FlashPrepareResult;
import com.eventhub.inventory.api.model.FlashReservationSettleResult;

public interface FlashAdmissionApi {

    FlashPrepareResult prepare(PrepareFlashAdmissionCommand command);

    FlashAdmissionResult reserve(ReserveFlashAdmissionCommand command);

    FlashReservationSettleResult activate(ActivateFlashAdmissionCommand command);

    FlashReservationSettleResult releasePending(ReleasePendingFlashAdmissionCommand command);
}

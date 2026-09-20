package com.eventhub.event.api;

import com.eventhub.event.api.command.RejectEventCommand;
import com.eventhub.event.api.model.ReviewEventView;

public interface AdminEventReviewApi {

    ReviewEventView getReviewEvent(long eventId);

    ReviewEventView approveEvent(long eventId);

    ReviewEventView rejectEvent(long eventId, RejectEventCommand command);
}

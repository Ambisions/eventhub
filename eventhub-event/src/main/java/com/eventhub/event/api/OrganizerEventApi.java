package com.eventhub.event.api;

import com.eventhub.event.api.command.CreateEventCommand;
import com.eventhub.event.api.command.CreateEventSessionCommand;
import com.eventhub.event.api.command.UpdateEventCommand;
import com.eventhub.event.api.command.UpdateEventSessionCommand;
import com.eventhub.event.api.model.EventSessionView;
import com.eventhub.event.api.model.ManagedEventView;

public interface OrganizerEventApi {

    ManagedEventView getOwnedEvent(long currentUserId, long eventId);

    ManagedEventView createEvent(long currentUserId, CreateEventCommand command);

    ManagedEventView updateEvent(long currentUserId, long eventId, UpdateEventCommand command);

    EventSessionView createSession(long currentUserId, long eventId, CreateEventSessionCommand command);

    EventSessionView updateSession(long currentUserId, long sessionId, UpdateEventSessionCommand command);

    ManagedEventView submitEvent(long currentUserId, long eventId);
}

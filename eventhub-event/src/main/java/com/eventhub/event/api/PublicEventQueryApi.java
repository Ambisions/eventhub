package com.eventhub.event.api;

import java.util.List;

import com.eventhub.event.api.model.EventSessionView;
import com.eventhub.event.api.model.PublicEventView;
import com.eventhub.event.api.model.PublishedEventPage;

public interface PublicEventQueryApi {

    PublishedEventPage listPublishedEvents(int page, int size);

    PublicEventView getPublishedEvent(long eventId);

    List<EventSessionView> listPublishedSessions(long eventId);

    EventSessionView getPublishedSession(long sessionId);
}

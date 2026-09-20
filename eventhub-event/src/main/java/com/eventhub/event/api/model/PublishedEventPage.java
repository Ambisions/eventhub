package com.eventhub.event.api.model;

import java.util.List;

public record PublishedEventPage(
        List<PublicEventView> items,
        int page,
        int size,
        boolean hasNext) {

    public PublishedEventPage {
        items = List.copyOf(items);
    }
}

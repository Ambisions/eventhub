package com.eventhub.controller.event.dto;

import java.util.List;

import com.eventhub.event.api.model.PublishedEventPage;

public record PublishedEventPageResponse(
        List<PublicEventResponse> items,
        int page,
        int size,
        boolean hasNext) {

    public static PublishedEventPageResponse from(PublishedEventPage page) {
        return new PublishedEventPageResponse(
                page.items().stream().map(PublicEventResponse::from).toList(),
                page.page(),
                page.size(),
                page.hasNext());
    }
}

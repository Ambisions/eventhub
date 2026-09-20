package com.eventhub.controller.event;

import java.util.List;

import com.eventhub.controller.event.dto.EventSessionResponse;
import com.eventhub.controller.event.dto.PublicEventResponse;
import com.eventhub.controller.event.dto.PublishedEventPageResponse;
import com.eventhub.event.api.PublicEventQueryApi;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
public class PublicEventController {

    private final PublicEventQueryApi publicEventQueryApi;

    public PublicEventController(PublicEventQueryApi publicEventQueryApi) {
        this.publicEventQueryApi = publicEventQueryApi;
    }

    @GetMapping("/api/events")
    public PublishedEventPageResponse listPublishedEvents(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return PublishedEventPageResponse.from(publicEventQueryApi.listPublishedEvents(page, size));
    }

    @GetMapping("/api/events/{eventId}")
    public PublicEventResponse getPublishedEvent(@PathVariable long eventId) {
        return PublicEventResponse.from(publicEventQueryApi.getPublishedEvent(eventId));
    }

    @GetMapping("/api/events/{eventId}/sessions")
    public List<EventSessionResponse> listPublishedSessions(@PathVariable long eventId) {
        return publicEventQueryApi.listPublishedSessions(eventId).stream()
                .map(EventSessionResponse::from)
                .toList();
    }

    @GetMapping("/api/sessions/{sessionId}")
    public EventSessionResponse getPublishedSession(@PathVariable long sessionId) {
        return EventSessionResponse.from(publicEventQueryApi.getPublishedSession(sessionId));
    }
}

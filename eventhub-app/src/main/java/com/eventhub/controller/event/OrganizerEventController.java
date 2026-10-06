package com.eventhub.controller.event;

import com.eventhub.controller.event.dto.CreateEventRequest;
import com.eventhub.controller.event.dto.CreateEventSessionRequest;
import com.eventhub.controller.event.dto.EventSessionResponse;
import com.eventhub.controller.event.dto.ManagedEventResponse;
import com.eventhub.controller.event.dto.UpdateEventRequest;
import com.eventhub.controller.event.dto.UpdateEventSessionRequest;
import com.eventhub.event.api.OrganizerEventApi;
import com.eventhub.event.api.command.CreateEventCommand;
import com.eventhub.event.api.command.CreateEventSessionCommand;
import com.eventhub.event.api.command.UpdateEventCommand;
import com.eventhub.event.api.command.UpdateEventSessionCommand;
import com.eventhub.security.EventHubAuthenticatedUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/organizer")
public final class OrganizerEventController {

    private final OrganizerEventApi organizerEventApi;

    public OrganizerEventController(OrganizerEventApi organizerEventApi) {
        this.organizerEventApi = organizerEventApi;
    }

    @GetMapping("/events/{eventId}")
    public ManagedEventResponse getOwnedEvent(
            @AuthenticationPrincipal EventHubAuthenticatedUser currentUser,
            @PathVariable long eventId) {
        return ManagedEventResponse.from(organizerEventApi.getOwnedEvent(currentUser.userId(), eventId));
    }

    @PostMapping("/events")
    public ResponseEntity<ManagedEventResponse> createEvent(
            @AuthenticationPrincipal EventHubAuthenticatedUser currentUser,
            @Valid @RequestBody CreateEventRequest request) {
        ManagedEventResponse response = ManagedEventResponse.from(organizerEventApi.createEvent(
                currentUser.userId(),
                new CreateEventCommand(request.title(), request.description())));
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PutMapping("/events/{eventId}")
    public ManagedEventResponse updateEvent(
            @AuthenticationPrincipal EventHubAuthenticatedUser currentUser,
            @PathVariable long eventId,
            @Valid @RequestBody UpdateEventRequest request) {
        return ManagedEventResponse.from(organizerEventApi.updateEvent(
                currentUser.userId(), eventId,
                new UpdateEventCommand(request.title(), request.description())));
    }

    @PostMapping("/events/{eventId}/sessions")
    public ResponseEntity<EventSessionResponse> createSession(
            @AuthenticationPrincipal EventHubAuthenticatedUser currentUser,
            @PathVariable long eventId,
            @Valid @RequestBody CreateEventSessionRequest request) {
        EventSessionResponse response = EventSessionResponse.from(organizerEventApi.createSession(
                currentUser.userId(), eventId,
                new CreateEventSessionCommand(request.name(), request.venue(), request.saleMode(), request.saleStartAt(),
                        request.saleEndAt(), request.startAt(), request.endAt())));
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PutMapping("/sessions/{sessionId}")
    public EventSessionResponse updateSession(
            @AuthenticationPrincipal EventHubAuthenticatedUser currentUser,
            @PathVariable long sessionId,
            @Valid @RequestBody UpdateEventSessionRequest request) {
        return EventSessionResponse.from(organizerEventApi.updateSession(
                currentUser.userId(), sessionId,
                new UpdateEventSessionCommand(request.name(), request.venue(), request.saleMode(), request.saleStartAt(),
                        request.saleEndAt(), request.startAt(), request.endAt())));
    }

    @PostMapping("/events/{eventId}/submit")
    public ManagedEventResponse submitEvent(
            @AuthenticationPrincipal EventHubAuthenticatedUser currentUser,
            @PathVariable long eventId) {
        return ManagedEventResponse.from(organizerEventApi.submitEvent(currentUser.userId(), eventId));
    }
}

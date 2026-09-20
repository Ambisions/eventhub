package com.eventhub.controller.event;

import com.eventhub.controller.event.dto.RejectEventRequest;
import com.eventhub.controller.event.dto.ReviewEventResponse;
import com.eventhub.event.api.AdminEventReviewApi;
import com.eventhub.event.api.command.RejectEventCommand;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/events")
public final class AdminEventController {

    private final AdminEventReviewApi adminEventReviewApi;

    public AdminEventController(AdminEventReviewApi adminEventReviewApi) {
        this.adminEventReviewApi = adminEventReviewApi;
    }

    @GetMapping("/{eventId}")
    public ReviewEventResponse getReviewEvent(@PathVariable long eventId) {
        return ReviewEventResponse.from(adminEventReviewApi.getReviewEvent(eventId));
    }

    @PostMapping("/{eventId}/approve")
    public ReviewEventResponse approveEvent(@PathVariable long eventId) {
        return ReviewEventResponse.from(adminEventReviewApi.approveEvent(eventId));
    }

    @PostMapping("/{eventId}/reject")
    public ReviewEventResponse rejectEvent(
            @PathVariable long eventId,
            @Valid @RequestBody RejectEventRequest request) {
        return ReviewEventResponse.from(adminEventReviewApi.rejectEvent(
                eventId,
                new RejectEventCommand(request.reason())));
    }
}

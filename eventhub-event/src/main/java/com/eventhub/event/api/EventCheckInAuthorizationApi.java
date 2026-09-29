package com.eventhub.event.api;

import java.time.LocalDateTime;

public interface EventCheckInAuthorizationApi {

    void requireCheckInEligibility(
            long organizerUserId,
            long sessionId,
            LocalDateTime checkInTime);
}

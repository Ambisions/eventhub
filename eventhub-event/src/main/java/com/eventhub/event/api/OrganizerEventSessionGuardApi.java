package com.eventhub.event.api;

import com.eventhub.event.api.model.EditableEventSessionContext;

public interface OrganizerEventSessionGuardApi {

    EditableEventSessionContext requireEditableOwnedSession(long organizerUserId, long sessionId);
}

package com.eventhub.event.domain;

import com.eventhub.event.api.model.EventStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventStatePolicyTest {

    @Test
    void allowsEditingAndSubmittingOnlyDraftAndRejectedEvents() {
        assertTrue(EventStatePolicy.isEditable(EventStatus.DRAFT));
        assertTrue(EventStatePolicy.isEditable(EventStatus.REJECTED));
        assertFalse(EventStatePolicy.isEditable(EventStatus.PENDING_REVIEW));
        assertFalse(EventStatePolicy.isEditable(EventStatus.PUBLISHED));

        assertTrue(EventStatePolicy.canSubmit(EventStatus.DRAFT));
        assertTrue(EventStatePolicy.canSubmit(EventStatus.REJECTED));
        assertFalse(EventStatePolicy.canSubmit(EventStatus.PENDING_REVIEW));
        assertFalse(EventStatePolicy.canSubmit(EventStatus.PUBLISHED));
    }

    @Test
    void allowsReviewOnlyFromPendingReview() {
        assertTrue(EventStatePolicy.canReview(EventStatus.PENDING_REVIEW));
        assertFalse(EventStatePolicy.canReview(EventStatus.DRAFT));
        assertFalse(EventStatePolicy.canReview(EventStatus.REJECTED));
        assertFalse(EventStatePolicy.canReview(EventStatus.PUBLISHED));
    }
}

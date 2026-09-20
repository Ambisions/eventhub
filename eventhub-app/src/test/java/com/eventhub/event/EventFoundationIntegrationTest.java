package com.eventhub.event;

import java.time.LocalDateTime;
import java.util.UUID;

import com.eventhub.event.api.AdminEventReviewApi;
import com.eventhub.event.api.OrganizerEventApi;
import com.eventhub.event.api.PublicEventQueryApi;
import com.eventhub.event.api.command.CreateEventCommand;
import com.eventhub.event.api.command.CreateEventSessionCommand;
import com.eventhub.event.api.command.RejectEventCommand;
import com.eventhub.event.api.command.UpdateEventCommand;
import com.eventhub.event.api.command.UpdateEventSessionCommand;
import com.eventhub.event.api.error.EventErrorCode;
import com.eventhub.event.api.error.EventException;
import com.eventhub.event.api.model.EventSessionView;
import com.eventhub.event.api.model.EventStatus;
import com.eventhub.event.api.model.ManagedEventView;
import com.eventhub.event.api.model.PublicEventView;
import com.eventhub.event.mapper.EventMapper;
import com.eventhub.event.mapper.EventSessionMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.transaction.AfterTransaction;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class EventFoundationIntegrationTest {

    private static final String TEST_PREFIX = "phase5-event-";
    private static final long OWNER_ID = 9_001L;
    private static final long OTHER_USER_ID = 9_002L;

    @Autowired
    private OrganizerEventApi organizerEventApi;

    @Autowired
    private AdminEventReviewApi adminEventReviewApi;

    @Autowired
    private PublicEventQueryApi publicEventQueryApi;

    @Autowired
    private EventMapper eventMapper;

    @Autowired
    private EventSessionMapper eventSessionMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void createsDraftWithTrustedOwnerAndMapsEventStatusInBothDirections() {
        ManagedEventView created = createEvent("create");

        assertThat(created.id()).isPositive();
        assertThat(created.title()).startsWith(TEST_PREFIX);
        assertThat(created.status()).isEqualTo(EventStatus.DRAFT);
        assertThat(created.rejectionReason()).isNull();
        assertThat(created.createdAt()).isNotNull();
        assertThat(created.updatedAt()).isNotNull();
        assertThat(eventMapper.selectById(created.id()).getOrganizerUserId()).isEqualTo(OWNER_ID);
        assertThat(databaseStatus(created.id())).isEqualTo("DRAFT");
        assertThat(eventMapper.selectById(created.id()).getStatus()).isEqualTo(EventStatus.DRAFT);
    }

    @Test
    void updatesOnlyEditableOwnedEventsAndHandlesLegitimateNoOp() {
        ManagedEventView created = createEvent("update");

        ManagedEventView noOp = organizerEventApi.updateEvent(
                OWNER_ID,
                created.id(),
                new UpdateEventCommand(created.title(), created.description()));
        assertThat(noOp.id()).isEqualTo(created.id());

        ManagedEventView updated = organizerEventApi.updateEvent(
                OWNER_ID,
                created.id(),
                new UpdateEventCommand("  " + TEST_PREFIX + "updated  ", "updated description"));
        assertThat(updated.title()).isEqualTo(TEST_PREFIX + "updated");
        assertThat(updated.description()).isEqualTo("updated description");

        assertEventError(
                () -> organizerEventApi.updateEvent(
                        OTHER_USER_ID, created.id(), new UpdateEventCommand("Other", null)),
                EventErrorCode.EVENT_NOT_FOUND);

        organizerEventApi.submitEvent(OWNER_ID, created.id());
        assertEventError(
                () -> organizerEventApi.updateEvent(
                        OWNER_ID, created.id(), new UpdateEventCommand("Pending edit", null)),
                EventErrorCode.EVENT_NOT_EDITABLE);
    }

    @Test
    void enforcesSubmitReviewAndRejectionReasonStateMachine() {
        ManagedEventView created = createEvent("state");
        ManagedEventView pending = organizerEventApi.submitEvent(OWNER_ID, created.id());
        assertThat(pending.status()).isEqualTo(EventStatus.PENDING_REVIEW);
        assertThat(pending.rejectionReason()).isNull();

        ManagedEventView rejected = toManaged(adminEventReviewApi.rejectEvent(
                created.id(), new RejectEventCommand("  Needs a clearer agenda  ")));
        assertThat(rejected.status()).isEqualTo(EventStatus.REJECTED);
        assertThat(rejected.rejectionReason()).isEqualTo("Needs a clearer agenda");

        ManagedEventView edited = organizerEventApi.updateEvent(
                OWNER_ID,
                created.id(),
                new UpdateEventCommand(TEST_PREFIX + "state-edited", "new description"));
        assertThat(edited.status()).isEqualTo(EventStatus.REJECTED);

        ManagedEventView resubmitted = organizerEventApi.submitEvent(OWNER_ID, created.id());
        assertThat(resubmitted.status()).isEqualTo(EventStatus.PENDING_REVIEW);
        assertThat(resubmitted.rejectionReason()).isNull();

        assertThat(adminEventReviewApi.approveEvent(created.id()).status()).isEqualTo(EventStatus.PUBLISHED);
        assertThat(databaseReason(created.id())).isNull();
        assertEventError(
                () -> adminEventReviewApi.approveEvent(created.id()),
                EventErrorCode.ILLEGAL_EVENT_STATE);
    }

    @Test
    void locksParentForSessionMutationAndHidesChildrenUntilPublished() {
        ManagedEventView event = createEvent("session");
        SessionTimes times = sessionTimes();
        EventSessionView session = organizerEventApi.createSession(
                OWNER_ID,
                event.id(),
                new CreateEventSessionCommand(
                        "  " + TEST_PREFIX + "Main Session  ", "   ", times.saleStart(), times.saleEnd(),
                        times.start(), times.end()));

        assertThat(session.eventId()).isEqualTo(event.id());
        assertThat(session.name()).isEqualTo(TEST_PREFIX + "Main Session");
        assertThat(session.venue()).isNull();
        assertEventError(
                () -> publicEventQueryApi.listPublishedSessions(event.id()),
                EventErrorCode.EVENT_NOT_FOUND);
        assertEventError(
                () -> publicEventQueryApi.getPublishedSession(session.id()),
                EventErrorCode.SESSION_NOT_FOUND);

        EventSessionView updated = organizerEventApi.updateSession(
                OWNER_ID,
                session.id(),
                new UpdateEventSessionCommand(
                        "  " + TEST_PREFIX + "Updated Session  ", "  Hall A  ",
                        times.saleStart(), times.saleEnd(),
                        times.start(), times.end()));
        assertThat(updated.eventId()).isEqualTo(event.id());
        assertThat(updated.name()).isEqualTo(TEST_PREFIX + "Updated Session");
        assertThat(updated.venue()).isEqualTo("Hall A");

        organizerEventApi.submitEvent(OWNER_ID, event.id());
        adminEventReviewApi.approveEvent(event.id());
        assertThat(publicEventQueryApi.getPublishedSession(session.id()).name())
                .isEqualTo(TEST_PREFIX + "Updated Session");
        assertThat(publicEventQueryApi.listPublishedSessions(event.id())).hasSize(1);

        assertEventError(
                () -> organizerEventApi.updateSession(
                        OWNER_ID,
                        session.id(),
                        new UpdateEventSessionCommand(
                                TEST_PREFIX + "Too late", null, times.saleStart(), times.saleEnd(),
                                times.start(), times.end())),
                EventErrorCode.EVENT_NOT_EDITABLE);
    }

    @Test
    void exposesOnlyPublishedEventsAndUsesBoundedPagination() {
        ManagedEventView draft = createEvent("visibility-draft");
        ManagedEventView published = publishEvent("visibility-published");
        publishEvent("visibility-published-next");

        PublicEventView publicView = publicEventQueryApi.getPublishedEvent(published.id());
        assertThat(publicView.status()).isEqualTo(EventStatus.PUBLISHED);
        assertThat(publicView.id()).isEqualTo(published.id());
        assertEventError(
                () -> publicEventQueryApi.getPublishedEvent(draft.id()),
                EventErrorCode.EVENT_NOT_FOUND);

        var firstPage = publicEventQueryApi.listPublishedEvents(0, 1);
        assertThat(firstPage.items()).hasSize(1);
        assertThat(firstPage.hasNext()).isTrue();
        assertThat(firstPage.page()).isZero();
        assertThat(firstPage.size()).isEqualTo(1);
        assertEventError(
                () -> publicEventQueryApi.listPublishedEvents(-1, 20),
                EventErrorCode.INVALID_EVENT);
        assertEventError(
                () -> publicEventQueryApi.listPublishedEvents(0, 101),
                EventErrorCode.INVALID_EVENT);
    }

    @Test
    void masksNonOwnershipForManagedQueriesAndSessionMutations() {
        ManagedEventView event = createEvent("owner-mask");
        SessionTimes times = sessionTimes();

        assertEventError(
                () -> organizerEventApi.getOwnedEvent(OTHER_USER_ID, event.id()),
                EventErrorCode.EVENT_NOT_FOUND);
        assertEventError(
                () -> organizerEventApi.createSession(
                        OTHER_USER_ID,
                        event.id(),
                        new CreateEventSessionCommand(
                                "Session", null, times.saleStart(), times.saleEnd(),
                                times.start(), times.end())),
                EventErrorCode.EVENT_NOT_FOUND);
    }

    @AfterTransaction
    void verifiesTransactionRollbackRemovedSyntheticEventsAndSessions() {
        Long eventCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM events WHERE title LIKE ?",
                Long.class,
                TEST_PREFIX + "%");
        Long sessionCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM event_sessions WHERE name LIKE ?",
                Long.class,
                TEST_PREFIX + "%");
        assertThat(eventCount).isZero();
        assertThat(sessionCount).isZero();
    }

    private ManagedEventView createEvent(String scenario) {
        return organizerEventApi.createEvent(
                OWNER_ID,
                new CreateEventCommand(TEST_PREFIX + scenario + '-' + shortId(), "description"));
    }

    private ManagedEventView publishEvent(String scenario) {
        ManagedEventView event = createEvent(scenario);
        organizerEventApi.submitEvent(OWNER_ID, event.id());
        adminEventReviewApi.approveEvent(event.id());
        return event;
    }

    private String databaseStatus(long eventId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM events WHERE id = ?",
                String.class,
                eventId);
    }

    private String databaseReason(long eventId) {
        return jdbcTemplate.queryForObject(
                "SELECT rejection_reason FROM events WHERE id = ?",
                String.class,
                eventId);
    }

    private static ManagedEventView toManaged(com.eventhub.event.api.model.ReviewEventView view) {
        return new ManagedEventView(view.id(), view.title(), view.description(), view.status(),
                view.rejectionReason(), view.createdAt(), view.updatedAt());
    }

    private static SessionTimes sessionTimes() {
        LocalDateTime base = LocalDateTime.of(2030, 6, 1, 9, 0);
        return new SessionTimes(base, base.plusHours(1), base.plusHours(2), base.plusHours(4));
    }

    private static String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private static void assertEventError(Runnable action, EventErrorCode expected) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(EventException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(expected));
    }

    private record SessionTimes(
            LocalDateTime saleStart,
            LocalDateTime saleEnd,
            LocalDateTime start,
            LocalDateTime end) {
    }
}

package com.eventhub.event;

import java.time.LocalDateTime;
import java.util.UUID;

import com.eventhub.auth.api.AuthApi;
import com.eventhub.auth.api.command.LoginCommand;
import com.eventhub.auth.api.command.RegisterCommand;
import com.eventhub.auth.api.model.AccessToken;
import com.eventhub.event.api.AdminEventReviewApi;
import com.eventhub.event.api.OrganizerEventApi;
import com.eventhub.event.api.command.CreateEventCommand;
import com.eventhub.event.api.command.CreateEventSessionCommand;
import com.eventhub.event.api.command.RejectEventCommand;
import com.eventhub.event.api.model.ManagedEventView;
import com.eventhub.user.api.model.UserProfile;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.transaction.AfterTransaction;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class EventSecurityIntegrationTest {

    private static final String TEST_PREFIX = "phase5-event-http-";
    private static final String PASSWORD = "valid-password";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AuthApi authApi;

    @Autowired
    private OrganizerEventApi organizerEventApi;

    @Autowired
    private AdminEventReviewApi adminEventReviewApi;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void keepsPublicDetailPublishedOnlyAndIdentityInvariant() throws Exception {
        Identity owner = register("public-owner", false);
        Identity admin = register("public-admin", true);
        ManagedEventView published = organizerEventApi.createEvent(
                owner.profile().getId(), new CreateEventCommand(TEST_PREFIX + "published", "description"));
        organizerEventApi.submitEvent(owner.profile().getId(), published.id());
        adminEventReviewApi.approveEvent(published.id());

        String anonymousBody = publicEvent(published.id(), null, 200);
        String ownerBody = publicEvent(published.id(), owner.token().getTokenValue(), 200);
        String adminBody = publicEvent(published.id(), admin.token().getTokenValue(), 200);

        assertThat(objectMapper.readTree(ownerBody)).isEqualTo(objectMapper.readTree(anonymousBody));
        assertThat(objectMapper.readTree(adminBody)).isEqualTo(objectMapper.readTree(anonymousBody));
        JsonNode publicJson = objectMapper.readTree(anonymousBody);
        assertThat(publicJson.has("rejectionReason")).isFalse();
        assertThat(publicJson.has("organizerUserId")).isFalse();

        mockMvc.perform(get("/api/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.total").doesNotExist());

        ManagedEventView draft = organizerEventApi.createEvent(
                owner.profile().getId(), new CreateEventCommand(TEST_PREFIX + "draft", null));
        publicEvent(draft.id(), null, 404);
        publicEvent(draft.id(), owner.token().getTokenValue(), 404);
        publicEvent(draft.id(), admin.token().getTokenValue(), 404);
    }

    @Test
    void adminAuthorityDoesNotReplaceResourceOwnership() throws Exception {
        Identity owner = register("managed-owner", false);
        Identity otherUser = register("managed-other", false);
        Identity admin = register("managed-admin", true);
        ManagedEventView event = organizerEventApi.createEvent(
                owner.profile().getId(), new CreateEventCommand(TEST_PREFIX + "managed", null));
        organizerEventApi.submitEvent(owner.profile().getId(), event.id());
        adminEventReviewApi.rejectEvent(event.id(), new RejectEventCommand("Owner must revise"));

        mockMvc.perform(get("/api/organizer/events/{id}", event.id())
                        .header("Authorization", bearer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.rejectionReason").value("Owner must revise"))
                .andExpect(jsonPath("$.organizerUserId").doesNotExist());

        mockMvc.perform(get("/api/organizer/events/{id}", event.id())
                        .header("Authorization", bearer(otherUser)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EVENT_NOT_FOUND"));

        mockMvc.perform(get("/api/admin/events/{id}", event.id())
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.organizerUserId").value(owner.profile().getId()))
                .andExpect(jsonPath("$.rejectionReason").value("Owner must revise"));

        mockMvc.perform(put("/api/organizer/events/{id}", event.id())
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Admin is not owner\",\"description\":null}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EVENT_NOT_FOUND"));

        String adminOwnedBody = mockMvc.perform(post("/api/organizer/events")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + TEST_PREFIX + "admin-owned\",\"description\":null}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long adminOwnedEventId = objectMapper.readTree(adminOwnedBody).get("id").asLong();

        mockMvc.perform(put("/api/organizer/events/{id}", adminOwnedEventId)
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + TEST_PREFIX + "admin-owned-updated\"," +
                                "\"description\":\"updated by owner\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value(TEST_PREFIX + "admin-owned-updated"));
    }

    @Test
    void usesAuthenticatedPrincipalForCreateAndEnforcesAdminReviewAuthority() throws Exception {
        Identity owner = register("flow-owner", false);
        Identity user = register("flow-user", false);
        Identity admin = register("flow-admin", true);

        String createBody = mockMvc.perform(post("/api/organizer/events")
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"  " + TEST_PREFIX + "created  \","
                                + "\"description\":\"description\",\"organizerUserId\":999999}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.rejectionReason").isEmpty())
                .andReturn().getResponse().getContentAsString();
        long eventId = objectMapper.readTree(createBody).get("id").asLong();
        assertThat(databaseOwner(eventId)).isEqualTo(owner.profile().getId());

        mockMvc.perform(post("/api/organizer/events/{id}/submit", eventId)
                        .header("Authorization", bearer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_REVIEW"));

        mockMvc.perform(put("/api/organizer/events/{id}", eventId)
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Pending edit\",\"description\":null}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EVENT_NOT_EDITABLE"));

        mockMvc.perform(get("/api/admin/events/{id}", eventId)
                        .header("Authorization", bearer(user)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/admin/events/{id}/approve", eventId)
                        .header("Authorization", bearer(user)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/admin/events/{id}/reject", eventId)
                        .header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"denied\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/admin/events/{id}/reject", eventId)
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"  Needs revision  \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.rejectionReason").value("Needs revision"));

        mockMvc.perform(post("/api/organizer/events/{id}/submit", eventId)
                        .header("Authorization", bearer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rejectionReason").isEmpty());
        mockMvc.perform(post("/api/admin/events/{id}/approve", eventId)
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"));
    }

    @Test
    void hidesDraftSessionsAndRequiresAuthenticationForOrganizerRoutes() throws Exception {
        Identity owner = register("session-owner", false);
        ManagedEventView event = organizerEventApi.createEvent(
                owner.profile().getId(), new CreateEventCommand(TEST_PREFIX + "session-hidden", null));
        SessionTimes times = sessionTimes();
        long sessionId = organizerEventApi.createSession(
                owner.profile().getId(),
                event.id(),
                new CreateEventSessionCommand(
                        TEST_PREFIX + "Hidden Session", null, null,
                        times.saleStart(), times.saleEnd(), times.start(), times.end()))
                .id();

        mockMvc.perform(get("/api/events/{id}/sessions", event.id()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/sessions/{id}", sessionId))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/organizer/events/{id}", event.id()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/organizer/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Anonymous\"}"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/organizer/events")
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + "x".repeat(201) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_EVENT"));
    }

    @Test
    void exposesSaleModeAcrossOrganizerCreateUpdateAndPublicSessionResponses() throws Exception {
        Identity owner = register("sale-mode-owner", false);
        Identity admin = register("sale-mode-admin", true);
        ManagedEventView event = organizerEventApi.createEvent(
                owner.profile().getId(), new CreateEventCommand(TEST_PREFIX + "sale-mode", null));
        SessionTimes times = sessionTimes();

        String normalBody = mockMvc.perform(post("/api/organizer/events/{id}/sessions", event.id())
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sessionJson(times, null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.saleMode").value("NORMAL"))
                .andReturn().getResponse().getContentAsString();
        long normalSessionId = objectMapper.readTree(normalBody).get("id").asLong();

        mockMvc.perform(put("/api/organizer/sessions/{id}", normalSessionId)
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sessionJson(times, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.saleMode").value("NORMAL"));

        String flashBody = mockMvc.perform(post("/api/organizer/events/{id}/sessions", event.id())
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sessionJson(times, "FLASH")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.saleMode").value("FLASH"))
                .andReturn().getResponse().getContentAsString();
        long flashSessionId = objectMapper.readTree(flashBody).get("id").asLong();

        mockMvc.perform(put("/api/organizer/sessions/{id}", flashSessionId)
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sessionJson(times, "NORMAL")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.saleMode").value("NORMAL"));
        mockMvc.perform(put("/api/organizer/sessions/{id}", flashSessionId)
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sessionJson(times, "FLASH")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.saleMode").value("FLASH"));

        organizerEventApi.submitEvent(owner.profile().getId(), event.id());
        adminEventReviewApi.approveEvent(event.id());
        mockMvc.perform(get("/api/sessions/{id}", flashSessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.saleMode").value("FLASH"));
    }

    @AfterTransaction
    void verifiesTransactionRollbackRemovedHttpFixtures() {
        Long eventCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM events WHERE title LIKE ?", Long.class, TEST_PREFIX + "%");
        Long userCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE username LIKE ?", Long.class, TEST_PREFIX + "%");
        Long sessionCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM event_sessions WHERE name LIKE ?", Long.class, TEST_PREFIX + "%");
        assertThat(eventCount).isZero();
        assertThat(userCount).isZero();
        assertThat(sessionCount).isZero();
    }

    private Identity register(String scenario, boolean admin) {
        UserProfile profile = authApi.register(new RegisterCommand(
                TEST_PREFIX + scenario + '-' + shortId(), PASSWORD, scenario));
        if (admin) {
            jdbcTemplate.update("UPDATE users SET role = 'ADMIN' WHERE id = ?", profile.getId());
        }
        AccessToken token = authApi.login(new LoginCommand(profile.getUsername(), PASSWORD));
        return new Identity(profile, token);
    }

    private String publicEvent(long eventId, String token, int expectedStatus) throws Exception {
        var request = get("/api/events/{id}", eventId);
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return mockMvc.perform(request)
                .andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString();
    }

    private long databaseOwner(long eventId) {
        Long owner = jdbcTemplate.queryForObject(
                "SELECT organizer_user_id FROM events WHERE id = ?", Long.class, eventId);
        return owner == null ? -1 : owner;
    }

    private static String bearer(Identity identity) {
        return "Bearer " + identity.token().getTokenValue();
    }

    private static String sessionJson(SessionTimes times, String saleMode) {
        String mode = saleMode == null ? "" : ",\"saleMode\":\"" + saleMode + "\"";
        return "{\"name\":\"" + TEST_PREFIX + "HTTP Session\",\"venue\":\"Hall\""
                + mode
                + ",\"saleStartAt\":\"" + times.saleStart() + "\""
                + ",\"saleEndAt\":\"" + times.saleEnd() + "\""
                + ",\"startAt\":\"" + times.start() + "\""
                + ",\"endAt\":\"" + times.end() + "\"}";
    }

    private static SessionTimes sessionTimes() {
        LocalDateTime base = LocalDateTime.of(2032, 1, 1, 9, 0);
        return new SessionTimes(base, base.plusHours(1), base.plusHours(2), base.plusHours(4));
    }

    private static String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private record Identity(UserProfile profile, AccessToken token) {
    }

    private record SessionTimes(
            LocalDateTime saleStart,
            LocalDateTime saleEnd,
            LocalDateTime start,
            LocalDateTime end) {
    }
}

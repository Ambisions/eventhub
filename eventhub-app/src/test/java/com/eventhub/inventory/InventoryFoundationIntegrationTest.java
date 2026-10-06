package com.eventhub.inventory;

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
import com.eventhub.inventory.api.InventoryManagementApi;
import com.eventhub.inventory.api.InventoryReservationApi;
import com.eventhub.inventory.api.command.ConfigureInventoryCommand;
import com.eventhub.inventory.api.error.InventoryErrorCode;
import com.eventhub.inventory.api.error.InventoryException;
import com.eventhub.inventory.api.model.InventoryView;
import com.eventhub.inventory.mapper.InventoryMapper;
import com.eventhub.user.api.model.UserProfile;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class InventoryFoundationIntegrationTest {

    private static final String TEST_PREFIX = "phase6-inventory-";
    private static final String PASSWORD = "valid-password";

    @Autowired
    private InventoryManagementApi inventoryManagementApi;

    @Autowired
    private InventoryReservationApi inventoryReservationApi;

    @Autowired
    private InventoryMapper inventoryMapper;

    @Autowired
    private OrganizerEventApi organizerEventApi;

    @Autowired
    private AdminEventReviewApi adminEventReviewApi;

    @Autowired
    private AuthApi authApi;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void configuresAndReconfiguresUnusedInventoryWithExactMapping() {
        Fixture fixture = createFixture("configure");

        InventoryView created = inventoryManagementApi.configureInventory(
                fixture.owner().profile().getId(), new ConfigureInventoryCommand(fixture.sessionId(), 5));
        assertInventory(created, 5, 5, 0, 0);
        assertThat(created.updatedAt()).isNotNull();
        assertThat(inventoryMapper.selectBySessionId(fixture.sessionId()).getCreatedAt()).isNotNull();

        InventoryView same = inventoryManagementApi.configureInventory(
                fixture.owner().profile().getId(), new ConfigureInventoryCommand(fixture.sessionId(), 5));
        assertInventory(same, 5, 5, 0, 0);

        InventoryView resized = inventoryManagementApi.configureInventory(
                fixture.owner().profile().getId(), new ConfigureInventoryCommand(fixture.sessionId(), 8));
        assertInventory(resized, 8, 8, 0, 0);
    }

    @Test
    void configuresInventoryForRejectedEventThroughRealParentEventGuard() {
        Fixture fixture = createFixture("rejected-configure");
        organizerEventApi.submitEvent(fixture.owner().profile().getId(), fixture.eventId());
        adminEventReviewApi.rejectEvent(fixture.eventId(), new RejectEventCommand("Synthetic review reason"));

        InventoryView configured = inventoryManagementApi.configureInventory(
                fixture.owner().profile().getId(), new ConfigureInventoryCommand(fixture.sessionId(), 7));

        assertInventory(configured, 7, 7, 0, 0);
        assertInventory(current(fixture.sessionId()), 7, 7, 0, 0);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM events WHERE id = ?", String.class, fixture.eventId()))
                .isEqualTo("REJECTED");
    }

    @Test
    void appliesConditionalReservationMutationsAndPreservesInventoryInvariant() {
        Fixture fixture = createFixture("mutations");
        inventoryManagementApi.configureInventory(
                fixture.owner().profile().getId(), new ConfigureInventoryCommand(fixture.sessionId(), 2));

        inventoryReservationApi.lockOne(fixture.sessionId());
        assertInventory(current(fixture.sessionId()), 2, 1, 1, 0);
        assertInUseConflict(fixture.owner().profile().getId(), fixture.sessionId());

        inventoryReservationApi.releaseOne(fixture.sessionId());
        assertInventory(current(fixture.sessionId()), 2, 2, 0, 0);

        inventoryReservationApi.lockOne(fixture.sessionId());
        inventoryReservationApi.confirmSoldOne(fixture.sessionId());
        assertInventory(current(fixture.sessionId()), 2, 1, 0, 1);
        assertInUseConflict(fixture.owner().profile().getId(), fixture.sessionId());

        inventoryReservationApi.lockOne(fixture.sessionId());
        assertThatThrownBy(() -> inventoryReservationApi.lockOne(fixture.sessionId()))
                .isInstanceOfSatisfying(InventoryException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(InventoryErrorCode.SOLD_OUT));
        assertInventory(current(fixture.sessionId()), 2, 0, 1, 1);
    }

    @Test
    void exposesAuthenticatedOwnerHttpBoundaryWithoutAcceptingClientIdentity() throws Exception {
        Fixture ownerFixture = createFixture("http-owner");
        Identity other = register("http-other");

        mockMvc.perform(put("/api/organizer/sessions/{id}/inventory", ownerFixture.sessionId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"totalStock\":3}"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(put("/api/organizer/sessions/{id}/inventory", ownerFixture.sessionId())
                        .header("Authorization", bearer(ownerFixture.owner()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"totalStock\":3,\"organizerUserId\":999999}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value(ownerFixture.sessionId()))
                .andExpect(jsonPath("$.totalStock").value(3))
                .andExpect(jsonPath("$.availableStock").value(3))
                .andExpect(jsonPath("$.lockedStock").value(0))
                .andExpect(jsonPath("$.soldStock").value(0))
                .andExpect(jsonPath("$.id").doesNotExist());

        mockMvc.perform(put("/api/organizer/sessions/{id}/inventory", ownerFixture.sessionId())
                        .header("Authorization", bearer(other))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"totalStock\":4,\"organizerUserId\":"
                                + ownerFixture.owner().profile().getId() + "}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EVENT_NOT_FOUND"));
    }

    @Test
    void mapsHttpValidationAndNonEditableEventErrors() throws Exception {
        Fixture fixture = createFixture("http-errors");
        String authorization = bearer(fixture.owner());

        mockMvc.perform(put("/api/organizer/sessions/{id}/inventory", fixture.sessionId())
                        .header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mockMvc.perform(put("/api/organizer/sessions/{id}/inventory", fixture.sessionId())
                        .header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"totalStock\":-1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        organizerEventApi.submitEvent(fixture.owner().profile().getId(), fixture.eventId());
        mockMvc.perform(put("/api/organizer/sessions/{id}/inventory", fixture.sessionId())
                        .header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"totalStock\":2}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EVENT_NOT_EDITABLE"));
    }

    @AfterTransaction
    void verifiesRollbackRemovedInventoryFixtures() {
        assertThat(count("SELECT COUNT(*) FROM event_inventory i JOIN event_sessions s ON s.id = i.session_id "
                + "WHERE s.name LIKE ?", TEST_PREFIX + "%")).isZero();
        assertThat(count("SELECT COUNT(*) FROM event_sessions WHERE name LIKE ?", TEST_PREFIX + "%")).isZero();
        assertThat(count("SELECT COUNT(*) FROM events WHERE title LIKE ?", TEST_PREFIX + "%")).isZero();
        assertThat(count("SELECT COUNT(*) FROM users WHERE username LIKE ?", TEST_PREFIX + "%")).isZero();
    }

    private Fixture createFixture(String scenario) {
        Identity owner = register(scenario + "-owner");
        ManagedEventView event = organizerEventApi.createEvent(
                owner.profile().getId(), new CreateEventCommand(TEST_PREFIX + scenario + '-' + shortId(), null));
        SessionTimes times = sessionTimes();
        long sessionId = organizerEventApi.createSession(
                owner.profile().getId(), event.id(),
                new CreateEventSessionCommand(TEST_PREFIX + scenario + "-session-" + shortId(), null, null,
                        times.saleStart(), times.saleEnd(), times.start(), times.end())).id();
        return new Fixture(owner, event.id(), sessionId);
    }

    private Identity register(String scenario) {
        UserProfile profile = authApi.register(new RegisterCommand(
                TEST_PREFIX + scenario + '-' + shortId(), PASSWORD, scenario));
        AccessToken token = authApi.login(new LoginCommand(profile.getUsername(), PASSWORD));
        return new Identity(profile, token);
    }

    private InventoryView current(long sessionId) {
        var row = inventoryMapper.selectBySessionId(sessionId);
        return new InventoryView(row.getSessionId(), row.getTotalStock(), row.getAvailableStock(),
                row.getLockedStock(), row.getSoldStock(), row.getUpdatedAt());
    }

    private void assertInUseConflict(long ownerId, long sessionId) {
        assertThatThrownBy(() -> inventoryManagementApi.configureInventory(
                ownerId, new ConfigureInventoryCommand(sessionId, 9)))
                .isInstanceOfSatisfying(InventoryException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(InventoryErrorCode.INVENTORY_ALREADY_IN_USE));
    }

    private long count(String sql, String value) {
        Long count = jdbcTemplate.queryForObject(sql, Long.class, value);
        return count == null ? 0 : count;
    }

    private static void assertInventory(
            InventoryView view, int total, int available, int locked, int sold) {
        assertThat(view.totalStock()).isEqualTo(total);
        assertThat(view.availableStock()).isEqualTo(available);
        assertThat(view.lockedStock()).isEqualTo(locked);
        assertThat(view.soldStock()).isEqualTo(sold);
        assertThat(view.totalStock()).isEqualTo(view.availableStock() + view.lockedStock() + view.soldStock());
        assertThat(view.availableStock()).isNotNegative();
        assertThat(view.lockedStock()).isNotNegative();
        assertThat(view.soldStock()).isNotNegative();
    }

    private static String bearer(Identity identity) {
        return "Bearer " + identity.token().getTokenValue();
    }

    private static SessionTimes sessionTimes() {
        LocalDateTime base = LocalDateTime.of(2033, 1, 1, 9, 0);
        return new SessionTimes(base, base.plusHours(1), base.plusHours(2), base.plusHours(4));
    }

    private static String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private record Identity(UserProfile profile, AccessToken token) {
    }

    private record Fixture(Identity owner, long eventId, long sessionId) {
    }

    private record SessionTimes(
            LocalDateTime saleStart,
            LocalDateTime saleEnd,
            LocalDateTime start,
            LocalDateTime end) {
    }
}

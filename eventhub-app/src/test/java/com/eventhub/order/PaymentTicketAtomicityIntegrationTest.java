package com.eventhub.order;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.UUID;

import com.eventhub.application.notification.PaymentNotificationApplicationService;
import com.eventhub.handler.GlobalExceptionHandler;
import com.eventhub.notification.api.NotificationEventPublisherApi;
import com.eventhub.ticket.api.error.TicketErrorCode;
import com.eventhub.ticket.api.error.TicketException;
import com.eventhub.ticket.domain.TicketNumberGenerator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
@Import(PaymentTicketAtomicityIntegrationTest.FixedClockConfiguration.class)
class PaymentTicketAtomicityIntegrationTest {

    private static final String TEST_PREFIX = "phase7-payment-ticket-";
    private static final String ORDER_PREFIX = "P7B3-";
    private static final String ENCODED_PASSWORD = "{bcrypt}$2a$10$phase7.payment.ticket.synthetic";
    private static final Instant PAYMENT_INSTANT = Instant.parse("2035-06-07T08:09:10.123456Z");
    private static final LocalDateTime PAYMENT_NOW = LocalDateTime.ofInstant(PAYMENT_INSTANT, ZoneOffset.UTC);

    @Autowired
    private PaymentNotificationApplicationService paymentNotificationApplicationService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private GlobalExceptionHandler globalExceptionHandler;

    @MockitoBean
    private TicketNumberGenerator ticketNumberGenerator;

    @MockitoBean
    private NotificationEventPublisherApi notificationEventPublisherApi;

    @Test
    void rollsBackOrderAndInventoryWhenTicketGeneratorFails() {
        Fixture fixture = createFixture("unknown-ticket-failure");
        RuntimeException failure = new RuntimeException("synthetic ticket generation failure");
        when(ticketNumberGenerator.generate()).thenThrow(failure);

        assertThatThrownBy(() -> paymentNotificationApplicationService.pay(fixture.userId(), fixture.orderId()))
                .isSameAs(failure);

        assertRolledBackPayment(fixture);
        assertThat(ticketCountByOrder(fixture.orderId())).isZero();
        verifyNoInteractions(notificationEventPublisherApi);
    }

    @Test
    void rollsBackOrderAndInventoryWhenTicketNumberCollides() {
        Fixture existing = createFixture("ticket-number-source");
        String collisionTicketNo = syntheticTicketNo();
        insertTicket(collisionTicketNo, existing);
        Fixture target = createFixture("ticket-number-target");
        when(ticketNumberGenerator.generate()).thenReturn(collisionTicketNo);

        assertTicketError(
                () -> paymentNotificationApplicationService.pay(target.userId(), target.orderId()),
                TicketErrorCode.TICKET_ISSUANCE_FAILED);

        assertRolledBackPayment(target);
        assertThat(ticketCountByOrder(target.orderId())).isZero();
        assertThat(ticketCountByOrder(existing.orderId())).isEqualTo(1);
        verifyNoInteractions(notificationEventPublisherApi);
    }

    @Test
    void duplicateTicketConflictRollsBackOrderAndInventoryAndKeepsExistingTicket() {
        Fixture fixture = createFixture("duplicate-ticket");
        String existingTicketNo = syntheticTicketNo();
        insertTicket(existingTicketNo, fixture);
        when(ticketNumberGenerator.generate()).thenReturn(syntheticTicketNo());

        assertTicketError(
                () -> paymentNotificationApplicationService.pay(fixture.userId(), fixture.orderId()),
                TicketErrorCode.TICKET_ALREADY_ISSUED);

        assertRolledBackPayment(fixture);
        assertThat(ticketCountByOrder(fixture.orderId())).isEqualTo(1);
        assertThat(ticketNoByOrder(fixture.orderId())).isEqualTo(existingTicketNo);
        verifyNoInteractions(notificationEventPublisherApi);
    }

    @Test
    void mapsTicketErrorsForExistingPayHttpBoundary() {
        assertThat(globalExceptionHandler.handleTicketException(
                        new TicketException(TicketErrorCode.TICKET_NOT_FOUND)).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(globalExceptionHandler.handleTicketException(
                        new TicketException(TicketErrorCode.TICKET_ALREADY_ISSUED)).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(globalExceptionHandler.handleTicketException(
                        new TicketException(TicketErrorCode.TICKET_ISSUANCE_FAILED)).getStatusCode())
                .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @AfterEach
    void cleanupAndVerifyNoSyntheticResidue() {
        jdbcTemplate.update("DELETE t FROM ticket t JOIN event_sessions s ON s.id = t.session_id "
                + "WHERE s.name LIKE ?", TEST_PREFIX + "%");
        assertThat(count("SELECT COUNT(*) FROM ticket t JOIN event_sessions s ON s.id = t.session_id "
                + "WHERE s.name LIKE ?", TEST_PREFIX + "%")).isZero();
        jdbcTemplate.update("DELETE FROM ticket_order WHERE order_no LIKE ?", ORDER_PREFIX + "%");
        jdbcTemplate.update("DELETE i FROM event_inventory i JOIN event_sessions s ON s.id = i.session_id "
                + "WHERE s.name LIKE ?", TEST_PREFIX + "%");
        jdbcTemplate.update("DELETE FROM event_sessions WHERE name LIKE ?", TEST_PREFIX + "%");
        jdbcTemplate.update("DELETE FROM events WHERE title LIKE ?", TEST_PREFIX + "%");
        jdbcTemplate.update("DELETE FROM users WHERE username LIKE ?", TEST_PREFIX + "%");

        assertThat(count("SELECT COUNT(*) FROM ticket_order WHERE order_no LIKE ?", ORDER_PREFIX + "%")).isZero();
        assertThat(count("SELECT COUNT(*) FROM event_sessions WHERE name LIKE ?", TEST_PREFIX + "%")).isZero();
        assertThat(count("SELECT COUNT(*) FROM events WHERE title LIKE ?", TEST_PREFIX + "%")).isZero();
        assertThat(count("SELECT COUNT(*) FROM users WHERE username LIKE ?", TEST_PREFIX + "%")).isZero();
    }

    private Fixture createFixture(String scenario) {
        String suffix = scenario + '-' + shortId();
        String username = TEST_PREFIX + suffix;
        jdbcTemplate.update("INSERT INTO users (username, password_hash, display_name, role) "
                        + "VALUES (?, ?, ?, 'USER')",
                username, ENCODED_PASSWORD, scenario);
        long userId = jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE username = ?", Long.class, username);

        String title = TEST_PREFIX + suffix;
        jdbcTemplate.update("INSERT INTO events (organizer_user_id, title, status) VALUES (?, ?, 'PUBLISHED')",
                userId, title);
        long eventId = jdbcTemplate.queryForObject(
                "SELECT id FROM events WHERE title = ?", Long.class, title);

        String sessionName = TEST_PREFIX + suffix;
        jdbcTemplate.update("INSERT INTO event_sessions "
                        + "(event_id, name, sale_start_at, sale_end_at, start_at, end_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                eventId, sessionName, PAYMENT_NOW.minusHours(1), PAYMENT_NOW.plusHours(1),
                PAYMENT_NOW.plusHours(2), PAYMENT_NOW.plusHours(3));
        long sessionId = jdbcTemplate.queryForObject(
                "SELECT id FROM event_sessions WHERE name = ?", Long.class, sessionName);
        jdbcTemplate.update("INSERT INTO event_inventory "
                        + "(session_id, total_stock, available_stock, locked_stock, sold_stock) "
                        + "VALUES (?, 1, 0, 1, 0)",
                sessionId);

        String orderNo = ORDER_PREFIX
                + UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
        jdbcTemplate.update("INSERT INTO ticket_order "
                        + "(order_no, user_id, session_id, status, expire_at) "
                        + "VALUES (?, ?, ?, 'WAIT_PAY', ?)",
                orderNo, userId, sessionId, PAYMENT_NOW.plusMinutes(5));
        long orderId = jdbcTemplate.queryForObject(
                "SELECT id FROM ticket_order WHERE order_no = ?", Long.class, orderNo);
        return new Fixture(userId, sessionId, orderId);
    }

    private void insertTicket(String ticketNo, Fixture fixture) {
        jdbcTemplate.update("INSERT INTO ticket (ticket_no, order_id, user_id, session_id, status) "
                        + "VALUES (?, ?, ?, ?, 'VALID')",
                ticketNo, fixture.orderId(), fixture.userId(), fixture.sessionId());
    }

    private void assertRolledBackPayment(Fixture fixture) {
        assertThat(orderStatus(fixture.orderId())).isEqualTo("WAIT_PAY");
        assertThat(inventory(fixture.sessionId())).isEqualTo(new InventoryState(1, 0, 1, 0));
        assertInventoryInvariant(fixture.sessionId());
    }

    private String orderStatus(long orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM ticket_order WHERE id = ?", String.class, orderId);
    }

    private InventoryState inventory(long sessionId) {
        return jdbcTemplate.queryForObject(
                "SELECT total_stock, available_stock, locked_stock, sold_stock "
                        + "FROM event_inventory WHERE session_id = ?",
                (resultSet, rowNumber) -> new InventoryState(
                        resultSet.getInt("total_stock"),
                        resultSet.getInt("available_stock"),
                        resultSet.getInt("locked_stock"),
                        resultSet.getInt("sold_stock")),
                sessionId);
    }

    private void assertInventoryInvariant(long sessionId) {
        InventoryState state = inventory(sessionId);
        assertThat(state.total()).isEqualTo(state.available() + state.locked() + state.sold());
        assertThat(state.available()).isNotNegative();
        assertThat(state.locked()).isNotNegative();
        assertThat(state.sold()).isNotNegative();
    }

    private long ticketCountByOrder(long orderId) {
        return count("SELECT COUNT(*) FROM ticket WHERE order_id = ?", orderId);
    }

    private String ticketNoByOrder(long orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT ticket_no FROM ticket WHERE order_id = ?", String.class, orderId);
    }

    private long count(String sql, Object... arguments) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, arguments);
        return value == null ? 0 : value;
    }

    private static void assertTicketError(Runnable invocation, TicketErrorCode expected) {
        assertThatThrownBy(invocation::run)
                .isInstanceOfSatisfying(TicketException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(expected));
    }

    private static String syntheticTicketNo() {
        return "TKT-" + UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
    }

    private static String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private record Fixture(long userId, long sessionId, long orderId) {
    }

    private record InventoryState(int total, int available, int locked, int sold) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfiguration {

        @Bean
        @Primary
        Clock paymentTicketClock() {
            return Clock.fixed(PAYMENT_INSTANT, ZoneOffset.UTC);
        }
    }
}

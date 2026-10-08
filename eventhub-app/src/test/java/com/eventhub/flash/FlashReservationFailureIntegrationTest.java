package com.eventhub.flash;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import com.eventhub.application.flash.FlashReservationApplicationService;
import com.eventhub.application.flash.error.FlashErrorCode;
import com.eventhub.application.flash.error.FlashException;
import com.eventhub.inventory.api.FlashAdmissionApi;
import com.eventhub.inventory.api.command.ActivateFlashAdmissionCommand;
import com.eventhub.inventory.api.command.PrepareFlashAdmissionCommand;
import com.eventhub.inventory.api.command.ReleasePendingFlashAdmissionCommand;
import com.eventhub.inventory.api.command.ReserveFlashAdmissionCommand;
import com.eventhub.inventory.api.error.FlashAdmissionUnavailableException;
import com.eventhub.inventory.api.model.FlashAdmissionResult;
import com.eventhub.inventory.api.model.FlashPrepareResult;
import com.eventhub.inventory.api.model.FlashReservationSettleResult;
import com.eventhub.order.api.ReservationApi;
import com.eventhub.order.api.model.OrderView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import(FlashReservationFailureIntegrationTest.FailureTestConfiguration.class)
class FlashReservationFailureIntegrationTest {

    private static final String PREFIX = "phase10-flash-failure-";
    private static final String ENCODED_PASSWORD = "{bcrypt}$2a$10$phase10.synthetic.credential";

    @Autowired
    private FlashReservationApplicationService service;

    @Autowired
    private ControlledReservationApi controlledReservationApi;

    @Autowired
    private ControlledFlashAdmissionApi controlledFlashAdmissionApi;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private final List<Long> sessionIds = new ArrayList<>();
    private final List<Long> eventIds = new ArrayList<>();
    private final List<Long> userIds = new ArrayList<>();

    @AfterEach
    void cleanup() {
        controlledReservationApi.reset();
        controlledFlashAdmissionApi.reset();
        for (long sessionId : sessionIds) {
            redisTemplate.delete(List.of(metaKey(sessionId), admissionsKey(sessionId)));
            jdbcTemplate.update("DELETE FROM ticket_order WHERE session_id = ?", sessionId);
            jdbcTemplate.update("DELETE FROM event_inventory WHERE session_id = ?", sessionId);
            jdbcTemplate.update("DELETE FROM event_sessions WHERE id = ?", sessionId);
        }
        for (long eventId : eventIds) {
            jdbcTemplate.update("DELETE FROM events WHERE id = ?", eventId);
        }
        for (long userId : userIds) {
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
        }
        assertThat(count("SELECT COUNT(*) FROM event_sessions WHERE name LIKE ?", PREFIX + "%")).isZero();
    }

    @Test
    void simulatedAmbiguousDatabaseOutcomeLeavesPendingAdmissionAndDoesNotCompensate() {
        Fixture fixture = fixture("ambiguous", 2);
        controlledReservationApi.failAmbiguously();

        assertThatThrownBy(() -> service.reserve(fixture.userId(), fixture.sessionId()))
                .isInstanceOf(FlashException.class)
                .extracting(exception -> ((FlashException) exception).getErrorCode())
                .isEqualTo(FlashErrorCode.FLASH_SERVICE_UNAVAILABLE);

        assertThat(orderCount(fixture.sessionId())).isZero();
        assertThat(redisTemplate.opsForHash().get(metaKey(fixture.sessionId()), "stock")).isEqualTo("1");
        assertThat(redisTemplate.opsForHash().get(
                admissionsKey(fixture.sessionId()), Long.toString(fixture.userId())))
                .asString().startsWith("P:");
        assertThat(controlledFlashAdmissionApi.releaseCalls()).isZero();
    }

    @Test
    void activateInfrastructureFailureAfterCommitPreservesWaitPaySuccessAndLeavesPending() {
        Fixture fixture = fixture("activate-failure", 2);
        controlledFlashAdmissionApi.failActivate();

        OrderView order = service.reserve(fixture.userId(), fixture.sessionId());

        assertThat(orderCount(fixture.sessionId())).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM ticket_order WHERE id = ? AND status = 'WAIT_PAY'",
                order.id())).isEqualTo(1);
        assertThat(inventory(fixture.sessionId())).isEqualTo(new InventoryState(2, 1, 1, 0));
        assertThat(redisTemplate.opsForHash().get(metaKey(fixture.sessionId()), "stock")).isEqualTo("1");
        assertThat(redisTemplate.opsForHash().get(
                admissionsKey(fixture.sessionId()), Long.toString(fixture.userId())))
                .asString().startsWith("P:");
        assertThat(controlledFlashAdmissionApi.releaseCalls()).isZero();
    }

    private Fixture fixture(String scenario, int stock) {
        String suffix = scenario + '-' + shortId();
        String username = PREFIX + suffix;
        jdbcTemplate.update("INSERT INTO users (username, password_hash, display_name, role) "
                        + "VALUES (?, ?, ?, 'USER')", username, ENCODED_PASSWORD, scenario);
        long userId = requiredLong("SELECT id FROM users WHERE username = ?", username);
        userIds.add(userId);

        String title = PREFIX + "event-" + suffix;
        jdbcTemplate.update("INSERT INTO events (organizer_user_id, title, status) VALUES (?, ?, 'PUBLISHED')",
                userId, title);
        long eventId = requiredLong("SELECT id FROM events WHERE title = ?", title);
        eventIds.add(eventId);

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        LocalDateTime start = now.minusMinutes(2);
        LocalDateTime end = now.plusMinutes(10);
        String sessionName = PREFIX + "session-" + suffix;
        jdbcTemplate.update("""
                INSERT INTO event_sessions (
                    event_id, name, sale_mode, sale_start_at, sale_end_at, start_at, end_at)
                VALUES (?, ?, 'FLASH', ?, ?, ?, ?)
                """, eventId, sessionName, start, end, end.plusHours(1), end.plusHours(2));
        long sessionId = requiredLong("SELECT id FROM event_sessions WHERE name = ?", sessionName);
        sessionIds.add(sessionId);
        jdbcTemplate.update("""
                INSERT INTO event_inventory (
                    session_id, total_stock, available_stock, locked_stock, sold_stock)
                VALUES (?, ?, ?, 0, 0)
                """, sessionId, stock, stock);

        redisTemplate.opsForHash().putAll(metaKey(sessionId), Map.of(
                "state", "PREPARED",
                "capacity", Integer.toString(stock),
                "stock", Integer.toString(stock),
                "saleStartUs", Long.toString(epochMicros(start)),
                "saleEndUs", Long.toString(epochMicros(end))));
        return new Fixture(sessionId, userId);
    }

    private InventoryState inventory(long sessionId) {
        return jdbcTemplate.queryForObject(
                "SELECT total_stock, available_stock, locked_stock, sold_stock "
                        + "FROM event_inventory WHERE session_id = ?",
                (resultSet, rowNum) -> new InventoryState(
                        resultSet.getInt("total_stock"),
                        resultSet.getInt("available_stock"),
                        resultSet.getInt("locked_stock"),
                        resultSet.getInt("sold_stock")),
                sessionId);
    }

    private long orderCount(long sessionId) {
        return count("SELECT COUNT(*) FROM ticket_order WHERE session_id = ?", sessionId);
    }

    private long count(String sql, Object... arguments) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, arguments);
        return value == null ? 0L : value;
    }

    private long requiredLong(String sql, Object... arguments) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, arguments);
        if (value == null) {
            throw new IllegalStateException("fixture id was not generated");
        }
        return value;
    }

    private static long epochMicros(LocalDateTime value) {
        Instant instant = value.toInstant(ZoneOffset.UTC);
        return Math.addExact(Math.multiplyExact(instant.getEpochSecond(), 1_000_000L),
                instant.getNano() / 1_000L);
    }

    private static String metaKey(long sessionId) {
        return "eventhub:flash:{" + sessionId + "}:meta";
    }

    private static String admissionsKey(long sessionId) {
        return "eventhub:flash:{" + sessionId + "}:admissions";
    }

    private static String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private record Fixture(long sessionId, long userId) {
    }

    private record InventoryState(int total, int available, int locked, int sold) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FailureTestConfiguration {

        @Bean
        @Primary
        ControlledReservationApi controlledReservationApi(
                @Qualifier("orderService") ReservationApi delegate) {
            return new ControlledReservationApi(delegate);
        }

        @Bean
        @Primary
        ControlledFlashAdmissionApi controlledFlashAdmissionApi(
                @Qualifier("redisFlashAdmissionAdapter") FlashAdmissionApi delegate) {
            return new ControlledFlashAdmissionApi(delegate);
        }
    }

    static final class ControlledReservationApi implements ReservationApi {

        private final ReservationApi delegate;
        private final AtomicBoolean ambiguousFailure = new AtomicBoolean();

        private ControlledReservationApi(ReservationApi delegate) {
            this.delegate = delegate;
        }

        void failAmbiguously() {
            ambiguousFailure.set(true);
        }

        void reset() {
            ambiguousFailure.set(false);
        }

        @Override
        public OrderView reserve(long userId, long sessionId) {
            return delegate.reserve(userId, sessionId);
        }

        @Override
        public OrderView reserveFlash(long userId, long sessionId) {
            if (ambiguousFailure.get()) {
                throw new DataAccessResourceFailureException("synthetic ambiguous outcome");
            }
            return delegate.reserveFlash(userId, sessionId);
        }
    }

    static final class ControlledFlashAdmissionApi implements FlashAdmissionApi {

        private final FlashAdmissionApi delegate;
        private final AtomicBoolean activateFailure = new AtomicBoolean();
        private int releaseCalls;

        private ControlledFlashAdmissionApi(FlashAdmissionApi delegate) {
            this.delegate = delegate;
        }

        void failActivate() {
            activateFailure.set(true);
        }

        int releaseCalls() {
            return releaseCalls;
        }

        void reset() {
            activateFailure.set(false);
            releaseCalls = 0;
        }

        @Override
        public FlashPrepareResult prepare(PrepareFlashAdmissionCommand command) {
            return delegate.prepare(command);
        }

        @Override
        public FlashAdmissionResult reserve(ReserveFlashAdmissionCommand command) {
            return delegate.reserve(command);
        }

        @Override
        public FlashReservationSettleResult activate(ActivateFlashAdmissionCommand command) {
            if (activateFailure.get()) {
                throw new FlashAdmissionUnavailableException("synthetic activate failure");
            }
            return delegate.activate(command);
        }

        @Override
        public FlashReservationSettleResult releasePending(ReleasePendingFlashAdmissionCommand command) {
            releaseCalls++;
            return delegate.releasePending(command);
        }
    }
}

package com.eventhub.flash;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import com.eventhub.inventory.api.FlashAdmissionApi;
import com.eventhub.inventory.api.command.ActivateFlashAdmissionCommand;
import com.eventhub.inventory.api.command.PrepareFlashAdmissionCommand;
import com.eventhub.inventory.api.command.ReleasePendingFlashAdmissionCommand;
import com.eventhub.inventory.api.command.ReserveFlashAdmissionCommand;
import com.eventhub.inventory.api.model.FlashAdmissionResult;
import com.eventhub.inventory.api.model.FlashPrepareResult;
import com.eventhub.inventory.api.model.FlashReservationSettleResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class FlashAdmissionRedisIntegrationTest {

    private static final AtomicLong SESSION_IDS = new AtomicLong(81_000_000L);
    private static final long TIMEOUT_SECONDS = 20L;

    @Autowired
    private FlashAdmissionApi flashAdmissionApi;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private final List<Long> testSessions = new ArrayList<>();

    @AfterEach
    void cleanOwnRedisKeys() {
        for (long sessionId : testSessions) {
            redisTemplate.delete(List.of(metaKey(sessionId), admissionsKey(sessionId)));
        }
    }

    @Test
    void prepareIsIdempotentWithoutResetAndRejectsConflictingOrMalformedState() {
        long sessionId = nextSession();
        long startUs = epochMicros(Instant.now().plusSeconds(300));
        long endUs = startUs + 3_600_000_000L;
        PrepareFlashAdmissionCommand command = new PrepareFlashAdmissionCommand(
                sessionId, 3, startUs, endUs);

        assertThat(flashAdmissionApi.prepare(command)).isEqualTo(FlashPrepareResult.PREPARED);
        assertThat(hash(metaKey(sessionId))).containsAllEntriesOf(Map.of(
                "state", "PREPARED",
                "capacity", "3",
                "stock", "3",
                "saleStartUs", Long.toString(startUs),
                "saleEndUs", Long.toString(endUs)));
        assertThat(redisTemplate.hasKey(admissionsKey(sessionId))).isFalse();
        assertThat(redisTemplate.getExpire(metaKey(sessionId))).isEqualTo(-1L);

        redisTemplate.opsForHash().put(metaKey(sessionId), "stock", "2");
        redisTemplate.opsForHash().put(admissionsKey(sessionId), "91", "P:admission-91");
        assertThat(flashAdmissionApi.prepare(command))
                .isEqualTo(FlashPrepareResult.IDEMPOTENT_PREPARED);
        assertThat(redisTemplate.opsForHash().get(metaKey(sessionId), "stock")).isEqualTo("2");
        assertThat(redisTemplate.opsForHash().get(admissionsKey(sessionId), "91"))
                .isEqualTo("P:admission-91");

        assertThat(flashAdmissionApi.prepare(new PrepareFlashAdmissionCommand(
                sessionId, 4, startUs, endUs))).isEqualTo(FlashPrepareResult.STATE_CONFLICT);
        assertThat(flashAdmissionApi.prepare(new PrepareFlashAdmissionCommand(
                sessionId, 3, startUs + 1, endUs))).isEqualTo(FlashPrepareResult.STATE_CONFLICT);
        assertThat(flashAdmissionApi.prepare(new PrepareFlashAdmissionCommand(
                sessionId, 3, startUs, endUs + 1))).isEqualTo(FlashPrepareResult.STATE_CONFLICT);
        assertThat(hash(metaKey(sessionId))).containsEntry("stock", "2");
        assertThat(hash(admissionsKey(sessionId))).containsEntry("91", "P:admission-91");

        long orphanSession = nextSession();
        redisTemplate.opsForHash().put(admissionsKey(orphanSession), "1", "P:orphan");
        assertThat(flashAdmissionApi.prepare(new PrepareFlashAdmissionCommand(
                orphanSession, 1, startUs, endUs))).isEqualTo(FlashPrepareResult.STATE_CONFLICT);
        assertThat(redisTemplate.hasKey(metaKey(orphanSession))).isFalse();

        assertMalformedPrepareState("missing-field", Map.of(
                "state", "PREPARED", "capacity", "3", "stock", "3",
                "saleStartUs", Long.toString(startUs)), startUs, endUs);
        assertMalformedPrepareState("bad-stock", Map.of(
                "state", "PREPARED", "capacity", "3", "stock", "x",
                "saleStartUs", Long.toString(startUs), "saleEndUs", Long.toString(endUs)),
                startUs, endUs);
        assertMalformedPrepareState("bad-capacity", Map.of(
                "state", "PREPARED", "capacity", "x", "stock", "3",
                "saleStartUs", Long.toString(startUs), "saleEndUs", Long.toString(endUs)),
                startUs, endUs);
        assertMalformedPrepareState("bad-state", Map.of(
                "state", "BROKEN", "capacity", "3", "stock", "3",
                "saleStartUs", Long.toString(startUs), "saleEndUs", Long.toString(endUs)),
                startUs, endUs);
        assertMalformedPrepareState("negative-stock", Map.of(
                "state", "PREPARED", "capacity", "3", "stock", "-1",
                "saleStartUs", Long.toString(startUs), "saleEndUs", Long.toString(endUs)),
                startUs, endUs);
        assertMalformedPrepareState("excess-stock", Map.of(
                "state", "PREPARED", "capacity", "3", "stock", "4",
                "saleStartUs", Long.toString(startUs), "saleEndUs", Long.toString(endUs)),
                startUs, endUs);

        long closedSession = nextSession();
        long pastStart = epochMicros(Instant.now().minusSeconds(1));
        assertThat(flashAdmissionApi.prepare(new PrepareFlashAdmissionCommand(
                closedSession, 1, pastStart, pastStart + 60_000_000L)))
                .isEqualTo(FlashPrepareResult.WINDOW_CLOSED);
        assertThat(redisTemplate.hasKey(metaKey(closedSession))).isFalse();
    }

    @Test
    void reserveEnforcesRedisTimeWindowStockAndSameUserSemantics() throws IOException {
        long missing = nextSession();
        assertReserveNoMutation(
                new ReserveFlashAdmissionCommand(missing, 1, "a"),
                FlashAdmissionResult.NOT_PREPARED);

        long before = nextSession();
        writeMeta(before, 1, 1, Instant.now().plusSeconds(60), Instant.now().plusSeconds(120));
        assertReserveNoMutation(
                new ReserveFlashAdmissionCommand(before, 1, "a"),
                FlashAdmissionResult.NOT_STARTED);

        Instant exactStart = Instant.parse("2030-01-01T00:00:00.123456Z");
        long exact = nextSession();
        writeMeta(exact, 1, 1, exactStart, exactStart.plusSeconds(60));
        assertThat(executeReserveAt(exact, 1, "exact", exactStart))
                .isEqualTo(FlashAdmissionResult.ADMITTED.name());

        long ended = nextSession();
        writeMeta(ended, 1, 1, exactStart.minusSeconds(60), exactStart);
        RedisState endedBefore = snapshot(ended);
        assertThat(executeReserveAt(ended, 1, "ended", exactStart))
                .isEqualTo(FlashAdmissionResult.ENDED.name());
        assertUnchanged(ended, endedBefore);

        long soldOut = nextSession();
        writeOpenMeta(soldOut, 1, 0);
        assertReserveNoMutation(
                new ReserveFlashAdmissionCommand(soldOut, 1, "a"),
                FlashAdmissionResult.SOLD_OUT);

        long malformedCapacity = nextSession();
        writeOpenMeta(malformedCapacity, 1, 1);
        redisTemplate.opsForHash().put(metaKey(malformedCapacity), "capacity", "broken");
        assertReserveNoMutation(
                new ReserveFlashAdmissionCommand(malformedCapacity, 1, "a"),
                FlashAdmissionResult.STATE_CONFLICT);

        long admitted = nextSession();
        writeOpenMeta(admitted, 3, 3);
        assertThat(flashAdmissionApi.reserve(new ReserveFlashAdmissionCommand(admitted, 7, "admission-7")))
                .isEqualTo(FlashAdmissionResult.ADMITTED);
        assertReserveNoMutation(
                new ReserveFlashAdmissionCommand(admitted, 7, "admission-7"),
                FlashAdmissionResult.IDEMPOTENT_PENDING);
        assertReserveNoMutation(
                new ReserveFlashAdmissionCommand(admitted, 7, "other"),
                FlashAdmissionResult.ALREADY_PENDING);
        assertThat(redisTemplate.opsForHash().get(metaKey(admitted), "stock")).isEqualTo("2");
        assertThat(redisTemplate.opsForHash().get(admissionsKey(admitted), "7"))
                .isEqualTo("P:admission-7");

        redisTemplate.opsForHash().put(admissionsKey(admitted), "8", "A:81");
        redisTemplate.opsForHash().put(admissionsKey(admitted), "9", "R:91");
        assertReserveNoMutation(
                new ReserveFlashAdmissionCommand(admitted, 8, "x"),
                FlashAdmissionResult.ALREADY_ADMITTED);
        assertReserveNoMutation(
                new ReserveFlashAdmissionCommand(admitted, 9, "x"),
                FlashAdmissionResult.ALREADY_ADMITTED);
    }

    @Test
    void settleEnforcesIdentityFencingAndReleaseIdempotency() {
        long missing = nextSession();
        assertThat(flashAdmissionApi.activate(new ActivateFlashAdmissionCommand(missing, 1, "a", 10)))
                .isEqualTo(FlashReservationSettleResult.NOT_PREPARED);

        long sessionId = nextSession();
        writeOpenMeta(sessionId, 3, 2);
        redisTemplate.opsForHash().put(admissionsKey(sessionId), "1", "P:admission-1");
        assertThat(flashAdmissionApi.activate(new ActivateFlashAdmissionCommand(
                sessionId, 1, "admission-1", 101)))
                .isEqualTo(FlashReservationSettleResult.ACTIVATED);
        assertThat(flashAdmissionApi.activate(new ActivateFlashAdmissionCommand(
                sessionId, 1, "admission-1", 101)))
                .isEqualTo(FlashReservationSettleResult.IDEMPOTENT_ACTIVATED);
        assertActivateNoMutation(sessionId, 1, "admission-1", 102,
                FlashReservationSettleResult.FENCE_CONFLICT);

        redisTemplate.opsForHash().put(admissionsKey(sessionId), "2", "P:admission-2");
        assertActivateNoMutation(sessionId, 2, "different", 201,
                FlashReservationSettleResult.FENCE_CONFLICT);
        redisTemplate.opsForHash().put(admissionsKey(sessionId), "3", "R:301");
        assertActivateNoMutation(sessionId, 3, "admission-3", 301,
                FlashReservationSettleResult.FENCE_CONFLICT);
        assertActivateNoMutation(sessionId, 4, "absent", 401,
                FlashReservationSettleResult.FENCE_CONFLICT);

        long malformedSettle = nextSession();
        writeOpenMeta(malformedSettle, 1, 0);
        redisTemplate.opsForHash().put(metaKey(malformedSettle), "state", "BROKEN");
        redisTemplate.opsForHash().put(admissionsKey(malformedSettle), "5", "P:admission-5");
        assertActivateNoMutation(malformedSettle, 5, "admission-5", 501,
                FlashReservationSettleResult.STATE_CONFLICT);

        long release = nextSession();
        writeOpenMeta(release, 2, 1);
        redisTemplate.opsForHash().put(admissionsKey(release), "11", "P:release-11");
        assertThat(flashAdmissionApi.releasePending(new ReleasePendingFlashAdmissionCommand(
                release, 11, "release-11")))
                .isEqualTo(FlashReservationSettleResult.RELEASED);
        assertThat(redisTemplate.opsForHash().get(metaKey(release), "stock")).isEqualTo("2");
        assertThat(flashAdmissionApi.releasePending(new ReleasePendingFlashAdmissionCommand(
                release, 11, "release-11")))
                .isEqualTo(FlashReservationSettleResult.IDEMPOTENT_RELEASED);
        assertThat(redisTemplate.opsForHash().get(metaKey(release), "stock")).isEqualTo("2");

        redisTemplate.opsForHash().put(admissionsKey(release), "12", "P:other");
        redisTemplate.opsForHash().put(admissionsKey(release), "13", "A:13");
        redisTemplate.opsForHash().put(admissionsKey(release), "14", "R:14");
        assertFence(release, 12, "expected");
        assertFence(release, 13, "expected");
        assertFence(release, 14, "expected");

        long corrupt = nextSession();
        writeOpenMeta(corrupt, 1, 1);
        redisTemplate.opsForHash().put(admissionsKey(corrupt), "15", "P:admission-15");
        assertThat(flashAdmissionApi.releasePending(new ReleasePendingFlashAdmissionCommand(
                corrupt, 15, "admission-15")))
                .isEqualTo(FlashReservationSettleResult.STATE_CONFLICT);
        assertThat(redisTemplate.opsForHash().get(metaKey(corrupt), "stock")).isEqualTo("1");
        assertThat(redisTemplate.opsForHash().get(admissionsKey(corrupt), "15"))
                .isEqualTo("P:admission-15");
    }

    @Test
    void capacityOneAdmitsExactlyOneConcurrentUserAndPreservesStableInvariant() throws Exception {
        long sessionId = nextSession();
        writeOpenMeta(sessionId, 1, 1);
        List<FlashAdmissionResult> results = concurrently(50, index -> flashAdmissionApi.reserve(
                new ReserveFlashAdmissionCommand(sessionId, index + 1, "capacity-" + index)));

        assertThat(results).filteredOn(FlashAdmissionResult.ADMITTED::equals).hasSize(1);
        assertThat(results).filteredOn(FlashAdmissionResult.SOLD_OUT::equals).hasSize(49);
        assertStableInvariant(sessionId, 1);
    }

    @Test
    void sameUserSameAdmissionIsLogicallyIdempotentUnderConcurrency() throws Exception {
        long sessionId = nextSession();
        writeOpenMeta(sessionId, 3, 3);
        List<FlashAdmissionResult> results = concurrently(20, index -> flashAdmissionApi.reserve(
                new ReserveFlashAdmissionCommand(sessionId, 71, "same-admission")));

        assertThat(results).filteredOn(FlashAdmissionResult.ADMITTED::equals).hasSize(1);
        assertThat(results).filteredOn(FlashAdmissionResult.IDEMPOTENT_PENDING::equals).hasSize(19);
        assertThat(redisTemplate.opsForHash().size(admissionsKey(sessionId))).isEqualTo(1L);
        assertStableInvariant(sessionId, 3);
    }

    @Test
    void sameUserDifferentAdmissionsHaveOneWinnerUnderConcurrency() throws Exception {
        long sessionId = nextSession();
        writeOpenMeta(sessionId, 3, 3);
        List<FlashAdmissionResult> results = concurrently(20, index -> flashAdmissionApi.reserve(
                new ReserveFlashAdmissionCommand(sessionId, 81, "different-" + index)));

        assertThat(results).filteredOn(FlashAdmissionResult.ADMITTED::equals).hasSize(1);
        assertThat(results).filteredOn(FlashAdmissionResult.ALREADY_PENDING::equals).hasSize(19);
        assertThat(redisTemplate.opsForHash().size(admissionsKey(sessionId))).isEqualTo(1L);
        assertStableInvariant(sessionId, 3);
    }

    private void assertMalformedPrepareState(
            String scenario, Map<String, String> state, long startUs, long endUs) {
        long sessionId = nextSession();
        redisTemplate.opsForHash().putAll(metaKey(sessionId), state);
        Map<Object, Object> before = redisTemplate.opsForHash().entries(metaKey(sessionId));
        assertThat(flashAdmissionApi.prepare(new PrepareFlashAdmissionCommand(
                sessionId, 3, startUs, endUs))).isEqualTo(FlashPrepareResult.STATE_CONFLICT);
        assertThat(redisTemplate.opsForHash().entries(metaKey(sessionId)))
                .as(scenario)
                .isEqualTo(before);
    }

    private void assertFence(long sessionId, long userId, String expectedAdmissionId) {
        RedisState before = snapshot(sessionId);
        assertThat(flashAdmissionApi.releasePending(new ReleasePendingFlashAdmissionCommand(
                sessionId, userId, expectedAdmissionId)))
                .isEqualTo(FlashReservationSettleResult.FENCE_CONFLICT);
        assertUnchanged(sessionId, before);
    }

    private void assertReserveNoMutation(
            ReserveFlashAdmissionCommand command, FlashAdmissionResult expected) {
        RedisState before = snapshot(command.sessionId());
        assertThat(flashAdmissionApi.reserve(command)).isEqualTo(expected);
        assertUnchanged(command.sessionId(), before);
    }

    private void assertActivateNoMutation(
            long sessionId,
            long userId,
            String admissionId,
            long orderId,
            FlashReservationSettleResult expected) {
        RedisState before = snapshot(sessionId);
        assertThat(flashAdmissionApi.activate(new ActivateFlashAdmissionCommand(
                sessionId, userId, admissionId, orderId))).isEqualTo(expected);
        assertUnchanged(sessionId, before);
    }

    private RedisState snapshot(long sessionId) {
        return new RedisState(hash(metaKey(sessionId)), hash(admissionsKey(sessionId)));
    }

    private void assertUnchanged(long sessionId, RedisState before) {
        assertThat(hash(metaKey(sessionId))).isEqualTo(before.meta());
        assertThat(hash(admissionsKey(sessionId))).isEqualTo(before.admissions());
    }

    private List<FlashAdmissionResult> concurrently(
            int workers, IndexedOperation operation) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<FlashAdmissionResult>> futures = new ArrayList<>();
        try {
            for (int index = 0; index < workers; index++) {
                int current = index;
                Callable<FlashAdmissionResult> task = () -> {
                    ready.countDown();
                    if (!start.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out waiting for FLASH concurrency start");
                    }
                    return operation.execute(current);
                };
                futures.add(executor.submit(task));
            }
            assertThat(ready.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<FlashAdmissionResult> results = new ArrayList<>();
            for (Future<FlashAdmissionResult> future : futures) {
                results.add(future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        }
    }

    private void assertStableInvariant(long sessionId, int capacity) {
        int stock = Integer.parseInt((String) redisTemplate.opsForHash().get(
                metaKey(sessionId), "stock"));
        long consuming = hash(admissionsKey(sessionId)).values().stream()
                .filter(value -> value.startsWith("P:") || value.startsWith("A:"))
                .count();
        assertThat(stock).isBetween(0, capacity);
        assertThat(stock + consuming).isEqualTo(capacity);
    }

    private String executeReserveAt(
            long sessionId, long userId, String admissionId, Instant fixedNow) throws IOException {
        String production = new ClassPathResource("lua/flash_reserve.lua")
                .getContentAsString(StandardCharsets.UTF_8);
        String wrapper = """
                local real_redis = redis
                local fixed_seconds = ARGV[3]
                local fixed_microseconds = ARGV[4]
                local redis = {}
                redis.call = function(command, ...)
                    if command == 'TIME' then
                        return {fixed_seconds, fixed_microseconds}
                    end
                    return real_redis.call(command, ...)
                end
                """ + production;
        return redisTemplate.execute(
                RedisScript.of(wrapper, String.class),
                List.of(metaKey(sessionId), admissionsKey(sessionId)),
                Long.toString(userId),
                admissionId,
                Long.toString(fixedNow.getEpochSecond()),
                String.format("%06d", fixedNow.getNano() / 1_000));
    }

    private void writeOpenMeta(long sessionId, int capacity, int stock) {
        writeMeta(sessionId, capacity, stock, Instant.now().minusSeconds(60), Instant.now().plusSeconds(300));
    }

    private void writeMeta(long sessionId, int capacity, int stock, Instant start, Instant end) {
        redisTemplate.opsForHash().putAll(metaKey(sessionId), Map.of(
                "state", "PREPARED",
                "capacity", Integer.toString(capacity),
                "stock", Integer.toString(stock),
                "saleStartUs", Long.toString(epochMicros(start)),
                "saleEndUs", Long.toString(epochMicros(end))));
    }

    private Map<String, String> hash(String key) {
        Map<Object, Object> raw = redisTemplate.opsForHash().entries(key);
        java.util.LinkedHashMap<String, String> result = new java.util.LinkedHashMap<>();
        raw.forEach((field, value) -> result.put(field.toString(), value.toString()));
        return result;
    }

    private long nextSession() {
        long sessionId = SESSION_IDS.incrementAndGet();
        testSessions.add(sessionId);
        redisTemplate.delete(List.of(metaKey(sessionId), admissionsKey(sessionId)));
        return sessionId;
    }

    private static long epochMicros(Instant instant) {
        return Math.addExact(
                Math.multiplyExact(instant.getEpochSecond(), 1_000_000L),
                instant.getNano() / 1_000L);
    }

    private static String metaKey(long sessionId) {
        return "eventhub:flash:{" + sessionId + "}:meta";
    }

    private static String admissionsKey(long sessionId) {
        return "eventhub:flash:{" + sessionId + "}:admissions";
    }

    @FunctionalInterface
    private interface IndexedOperation {
        FlashAdmissionResult execute(int index);
    }

    private record RedisState(Map<String, String> meta, Map<String, String> admissions) {
    }
}

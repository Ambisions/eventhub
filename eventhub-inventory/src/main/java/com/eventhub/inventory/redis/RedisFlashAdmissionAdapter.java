package com.eventhub.inventory.redis;

import java.util.List;
import java.util.Objects;

import com.eventhub.inventory.api.FlashAdmissionApi;
import com.eventhub.inventory.api.command.ActivateFlashAdmissionCommand;
import com.eventhub.inventory.api.command.PrepareFlashAdmissionCommand;
import com.eventhub.inventory.api.command.ReleasePendingFlashAdmissionCommand;
import com.eventhub.inventory.api.command.ReserveFlashAdmissionCommand;
import com.eventhub.inventory.api.error.FlashAdmissionUnavailableException;
import com.eventhub.inventory.api.model.FlashAdmissionResult;
import com.eventhub.inventory.api.model.FlashPrepareResult;
import com.eventhub.inventory.api.model.FlashReservationSettleResult;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

@Component
public final class RedisFlashAdmissionAdapter implements FlashAdmissionApi {

    private static final RedisScript<String> PREPARE_SCRIPT = RedisScript.of(
            new ClassPathResource("lua/flash_prepare.lua"), String.class);
    private static final RedisScript<String> RESERVE_SCRIPT = RedisScript.of(
            new ClassPathResource("lua/flash_reserve.lua"), String.class);
    private static final RedisScript<String> SETTLE_SCRIPT = RedisScript.of(
            new ClassPathResource("lua/flash_reservation_settle.lua"), String.class);

    private final StringRedisTemplate redisTemplate;

    public RedisFlashAdmissionAdapter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public FlashPrepareResult prepare(PrepareFlashAdmissionCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        validateSessionId(command.sessionId());
        if (command.capacity() < 0) {
            throw new IllegalArgumentException("capacity must not be negative");
        }
        validateWindow(command.saleStartUs(), command.saleEndUs());
        return execute(
                PREPARE_SCRIPT,
                keys(command.sessionId()),
                FlashPrepareResult.class,
                Integer.toString(command.capacity()),
                Long.toString(command.saleStartUs()),
                Long.toString(command.saleEndUs()));
    }

    @Override
    public FlashAdmissionResult reserve(ReserveFlashAdmissionCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        validateSessionId(command.sessionId());
        validatePositive(command.userId(), "userId");
        validateIdentifier(command.admissionId(), "admissionId");
        return execute(
                RESERVE_SCRIPT,
                keys(command.sessionId()),
                FlashAdmissionResult.class,
                Long.toString(command.userId()),
                command.admissionId());
    }

    @Override
    public FlashReservationSettleResult activate(ActivateFlashAdmissionCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        validateSessionId(command.sessionId());
        validatePositive(command.userId(), "userId");
        validateIdentifier(command.expectedAdmissionId(), "expectedAdmissionId");
        validatePositive(command.orderId(), "orderId");
        return execute(
                SETTLE_SCRIPT,
                keys(command.sessionId()),
                FlashReservationSettleResult.class,
                "ACTIVATE",
                Long.toString(command.userId()),
                command.expectedAdmissionId(),
                Long.toString(command.orderId()));
    }

    @Override
    public FlashReservationSettleResult releasePending(ReleasePendingFlashAdmissionCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        validateSessionId(command.sessionId());
        validatePositive(command.userId(), "userId");
        validateIdentifier(command.expectedAdmissionId(), "expectedAdmissionId");
        return execute(
                SETTLE_SCRIPT,
                keys(command.sessionId()),
                FlashReservationSettleResult.class,
                "RELEASE_PENDING",
                Long.toString(command.userId()),
                command.expectedAdmissionId(),
                "");
    }

    private List<String> keys(long sessionId) {
        return List.of(FlashRedisKeys.meta(sessionId), FlashRedisKeys.admissions(sessionId));
    }

    private <T extends Enum<T>> T execute(
            RedisScript<String> script,
            List<String> keys,
            Class<T> resultType,
            String... arguments) {
        final String reply;
        try {
            reply = redisTemplate.execute(script, keys, (Object[]) arguments);
        } catch (DataAccessException exception) {
            throw new FlashAdmissionUnavailableException("FLASH admission Redis operation failed", exception);
        }
        if (reply == null) {
            throw new FlashAdmissionUnavailableException("FLASH admission Redis operation returned no result");
        }
        try {
            return Enum.valueOf(resultType, reply);
        } catch (IllegalArgumentException exception) {
            throw new FlashAdmissionUnavailableException("FLASH admission Redis operation returned an unknown result");
        }
    }

    private static void validateSessionId(long sessionId) {
        validatePositive(sessionId, "sessionId");
    }

    private static void validatePositive(long value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private static void validateIdentifier(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private static void validateWindow(long saleStartUs, long saleEndUs) {
        if (saleStartUs < 0 || saleEndUs <= saleStartUs) {
            throw new IllegalArgumentException("sale window must be a positive, increasing epoch-microsecond range");
        }
    }
}

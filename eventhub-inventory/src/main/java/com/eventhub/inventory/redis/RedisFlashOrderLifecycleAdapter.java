package com.eventhub.inventory.redis;

import java.util.List;
import java.util.Objects;

import com.eventhub.inventory.api.FlashOrderLifecycleApi;
import com.eventhub.inventory.api.command.FlashOrderLifecycleCommand;
import com.eventhub.inventory.api.error.FlashAdmissionUnavailableException;
import com.eventhub.inventory.api.model.FlashOrderLifecycleResult;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

@Component
public final class RedisFlashOrderLifecycleAdapter implements FlashOrderLifecycleApi {

    private static final RedisScript<String> LIFECYCLE_SCRIPT = RedisScript.of(
            new ClassPathResource("lua/flash_order_lifecycle.lua"), String.class);

    private final StringRedisTemplate redisTemplate;

    public RedisFlashOrderLifecycleAdapter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public FlashOrderLifecycleResult releaseActive(FlashOrderLifecycleCommand command) {
        return execute("RELEASE", validate(command));
    }

    @Override
    public FlashOrderLifecycleResult refundActive(FlashOrderLifecycleCommand command) {
        return execute("REFUND", validate(command));
    }

    private FlashOrderLifecycleResult execute(String operation, FlashOrderLifecycleCommand command) {
        final String reply;
        try {
            reply = redisTemplate.execute(
                    LIFECYCLE_SCRIPT,
                    List.of(FlashRedisKeys.meta(command.sessionId()), FlashRedisKeys.admissions(command.sessionId())),
                    operation,
                    Long.toString(command.userId()),
                    Long.toString(command.orderId()));
        } catch (DataAccessException exception) {
            throw new FlashAdmissionUnavailableException("FLASH lifecycle Redis operation failed", exception);
        }
        if (reply == null) {
            throw new FlashAdmissionUnavailableException("FLASH lifecycle Redis operation returned no result");
        }
        try {
            return FlashOrderLifecycleResult.valueOf(reply);
        } catch (IllegalArgumentException exception) {
            throw new FlashAdmissionUnavailableException("FLASH lifecycle Redis operation returned an unknown result");
        }
    }

    private static FlashOrderLifecycleCommand validate(FlashOrderLifecycleCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        validatePositive(command.sessionId(), "sessionId");
        validatePositive(command.userId(), "userId");
        validatePositive(command.orderId(), "orderId");
        return command;
    }

    private static void validatePositive(long value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}

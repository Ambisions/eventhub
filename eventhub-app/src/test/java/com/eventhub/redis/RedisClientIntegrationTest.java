package com.eventhub.redis;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class RedisClientIntegrationTest {

    private static final Duration TEST_KEY_TTL = Duration.ofSeconds(60);

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Test
    void setsReadsAndDeletesStringValueWithTtl() {
        String key = "eventhub:test:redis-smoke:" + UUID.randomUUID();
        String value = "redis-client-smoke-value";

        try {
            stringRedisTemplate.opsForValue().set(key, value, TEST_KEY_TTL);

            assertThat(stringRedisTemplate.opsForValue().get(key)).isEqualTo(value);

            Long ttlSeconds = stringRedisTemplate.getExpire(key, TimeUnit.SECONDS);
            assertThat(ttlSeconds).isBetween(1L, TEST_KEY_TTL.toSeconds());

            assertThat(stringRedisTemplate.delete(key)).isTrue();
            assertThat(stringRedisTemplate.opsForValue().get(key)).isNull();
        }
        finally {
            stringRedisTemplate.delete(key);
        }
    }
}

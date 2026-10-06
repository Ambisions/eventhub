package com.eventhub.inventory.redis;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FlashRedisKeysTest {

    @Test
    void buildsExactClusterHashTaggedKeys() {
        assertEquals("eventhub:flash:{42}:meta", FlashRedisKeys.meta(42));
        assertEquals("eventhub:flash:{42}:admissions", FlashRedisKeys.admissions(42));
    }

    @Test
    void rejectsNonPositiveSessionId() {
        assertThrows(IllegalArgumentException.class, () -> FlashRedisKeys.meta(0));
        assertThrows(IllegalArgumentException.class, () -> FlashRedisKeys.admissions(-1));
    }
}

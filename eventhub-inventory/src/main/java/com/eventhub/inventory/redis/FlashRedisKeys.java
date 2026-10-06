package com.eventhub.inventory.redis;

final class FlashRedisKeys {

    private static final String PREFIX = "eventhub:flash:";

    private FlashRedisKeys() {
    }

    static String meta(long sessionId) {
        return PREFIX + hashTag(sessionId) + ":meta";
    }

    static String admissions(long sessionId) {
        return PREFIX + hashTag(sessionId) + ":admissions";
    }

    private static String hashTag(long sessionId) {
        if (sessionId <= 0) {
            throw new IllegalArgumentException("sessionId must be positive");
        }
        return "{" + sessionId + "}";
    }
}

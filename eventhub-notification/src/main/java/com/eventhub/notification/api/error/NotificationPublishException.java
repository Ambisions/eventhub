package com.eventhub.notification.api.error;

public final class NotificationPublishException extends RuntimeException {

    public NotificationPublishException(Throwable cause) {
        super("Notification event publication failed", cause);
    }
}

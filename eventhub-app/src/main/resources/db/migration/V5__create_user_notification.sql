CREATE TABLE user_notification (
    id BIGINT NOT NULL AUTO_INCREMENT,
    event_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    user_id BIGINT NOT NULL,
    type VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL,
    business_reference VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_user_notification PRIMARY KEY (id),
    CONSTRAINT uk_user_notification_event UNIQUE (event_id),
    CONSTRAINT uk_user_notification_business UNIQUE (type, business_reference),
    CONSTRAINT ck_user_notification_type CHECK (type IN ('TICKET_ISSUED'))
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4;

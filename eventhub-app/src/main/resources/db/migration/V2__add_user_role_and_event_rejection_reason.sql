ALTER TABLE users
    ADD COLUMN role VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs
        NOT NULL DEFAULT 'USER' AFTER display_name,
    ADD CONSTRAINT ck_users_role CHECK (role IN ('USER', 'ADMIN'));

ALTER TABLE events
    ADD COLUMN rejection_reason VARCHAR(500) NULL DEFAULT NULL AFTER status,
    ADD CONSTRAINT ck_events_rejection_reason CHECK (
        (
            status = 'REJECTED'
            AND rejection_reason IS NOT NULL
            AND CHAR_LENGTH(TRIM(rejection_reason)) > 0
        )
        OR
        (status <> 'REJECTED' AND rejection_reason IS NULL)
    );

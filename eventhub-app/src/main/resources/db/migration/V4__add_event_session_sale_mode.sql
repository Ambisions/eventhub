ALTER TABLE event_sessions
    ADD COLUMN sale_mode VARCHAR(16)
        CHARACTER SET utf8mb4
        COLLATE utf8mb4_0900_as_cs
        NOT NULL DEFAULT 'NORMAL'
        AFTER venue,
    ADD CONSTRAINT ck_event_sessions_sale_mode
        CHECK (sale_mode IN ('NORMAL', 'FLASH'));

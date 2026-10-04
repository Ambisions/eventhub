ALTER TABLE ticket_order
    DROP CHECK ck_ticket_order_status,
    MODIFY COLUMN purchase_guard TINYINT GENERATED ALWAYS AS (
        CASE
            WHEN status IN ('WAIT_PAY', 'PAID', 'REFUNDED') THEN 1
            ELSE NULL
        END
    ) STORED,
    ADD CONSTRAINT ck_ticket_order_status CHECK (
        status IN ('WAIT_PAY', 'PAID', 'CLOSED', 'CANCELLED', 'REFUNDED')
    );

ALTER TABLE ticket
    DROP CHECK ck_ticket_status,
    ADD CONSTRAINT ck_ticket_status CHECK (
        status IN ('VALID', 'USED', 'REFUNDED')
    );

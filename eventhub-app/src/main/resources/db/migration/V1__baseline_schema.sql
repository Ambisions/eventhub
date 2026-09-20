CREATE TABLE users (
    id BIGINT NOT NULL AUTO_INCREMENT,
    username VARCHAR(64) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    display_name VARCHAR(100) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_users PRIMARY KEY (id),
    CONSTRAINT uk_users_username UNIQUE (username)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4;

CREATE TABLE events (
    id BIGINT NOT NULL AUTO_INCREMENT,
    organizer_user_id BIGINT NOT NULL,
    title VARCHAR(200) NOT NULL,
    description TEXT NULL,
    status VARCHAR(32) COLLATE utf8mb4_0900_as_cs NOT NULL DEFAULT 'DRAFT',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_events PRIMARY KEY (id),
    CONSTRAINT ck_events_status CHECK (
        status IN ('DRAFT', 'PENDING_REVIEW', 'PUBLISHED', 'REJECTED')
    ),
    INDEX idx_events_organizer_created (organizer_user_id, created_at),
    INDEX idx_events_status_created (status, created_at)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4;

CREATE TABLE event_sessions (
    id BIGINT NOT NULL AUTO_INCREMENT,
    event_id BIGINT NOT NULL,
    name VARCHAR(200) NOT NULL,
    venue VARCHAR(255) NULL,
    sale_start_at DATETIME(6) NOT NULL,
    sale_end_at DATETIME(6) NOT NULL,
    start_at DATETIME(6) NOT NULL,
    end_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_event_sessions PRIMARY KEY (id),
    CONSTRAINT ck_event_sessions_sale_window CHECK (sale_start_at < sale_end_at),
    CONSTRAINT ck_event_sessions_runtime_window CHECK (start_at < end_at),
    INDEX idx_event_sessions_event_start (event_id, start_at)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4;

CREATE TABLE event_inventory (
    id BIGINT NOT NULL AUTO_INCREMENT,
    session_id BIGINT NOT NULL,
    total_stock INT NOT NULL,
    available_stock INT NOT NULL,
    locked_stock INT NOT NULL DEFAULT 0,
    sold_stock INT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_event_inventory PRIMARY KEY (id),
    CONSTRAINT uk_event_inventory_session UNIQUE (session_id),
    CONSTRAINT ck_event_inventory_total_nonnegative CHECK (total_stock >= 0),
    CONSTRAINT ck_event_inventory_available_nonnegative CHECK (available_stock >= 0),
    CONSTRAINT ck_event_inventory_locked_nonnegative CHECK (locked_stock >= 0),
    CONSTRAINT ck_event_inventory_sold_nonnegative CHECK (sold_stock >= 0),
    -- Core inventory conservation invariant.
    CONSTRAINT ck_event_inventory_balance CHECK (
        total_stock = available_stock + locked_stock + sold_stock
    )
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4;

CREATE TABLE ticket_order (
    id BIGINT NOT NULL AUTO_INCREMENT,
    order_no VARCHAR(64) NOT NULL,
    user_id BIGINT NOT NULL,
    session_id BIGINT NOT NULL,
    status VARCHAR(20) COLLATE utf8mb4_0900_as_cs NOT NULL DEFAULT 'WAIT_PAY',
    expire_at DATETIME(6) NOT NULL,
    -- Generated guard blocks duplicate effective orders but permits closed history.
    purchase_guard TINYINT GENERATED ALWAYS AS (
        CASE
            WHEN status IN ('WAIT_PAY', 'PAID') THEN 1
            ELSE NULL
        END
    ) STORED,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_ticket_order PRIMARY KEY (id),
    CONSTRAINT uk_ticket_order_order_no UNIQUE (order_no),
    CONSTRAINT uk_ticket_order_effective_purchase UNIQUE (user_id, session_id, purchase_guard),
    CONSTRAINT ck_ticket_order_status CHECK (
        status IN ('WAIT_PAY', 'PAID', 'CLOSED', 'CANCELLED')
    ),
    INDEX idx_ticket_order_user_created (user_id, created_at),
    INDEX idx_ticket_order_session (session_id),
    INDEX idx_ticket_order_status_expire (status, expire_at)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4;

CREATE TABLE ticket (
    id BIGINT NOT NULL AUTO_INCREMENT,
    ticket_no VARCHAR(64) NOT NULL,
    order_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    session_id BIGINT NOT NULL,
    status VARCHAR(20) COLLATE utf8mb4_0900_as_cs NOT NULL DEFAULT 'VALID',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_ticket PRIMARY KEY (id),
    CONSTRAINT uk_ticket_ticket_no UNIQUE (ticket_no),
    CONSTRAINT uk_ticket_order UNIQUE (order_id),
    -- V1 final guard: at most one issued ticket per user and session.
    CONSTRAINT uk_ticket_user_session UNIQUE (user_id, session_id),
    CONSTRAINT ck_ticket_status CHECK (status IN ('VALID', 'USED'))
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4;

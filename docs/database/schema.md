# EventHub V1 Baseline Schema

Status: `V1 / V2 / V3 / V4 FROZEN / IMPLEMENTED / RUNTIME VERIFIED`

This document explains the frozen schema design. The verified Flyway V1, V2, V3,
and V4 migrations are the executable source of truth for the current database. The
Phase 5 V2, Phase 9 V3, and Phase 10 V4 sections below record the implemented and
runtime-verified schema evolution.

## Scope

The first baseline contains exactly six business tables:

| Table | Responsibility |
| --- | --- |
| `users` | User identity and login credentials. |
| `events` | Event aggregate root and publication workflow state. |
| `event_sessions` | A sellable occurrence of an event and its sale/runtime window. |
| `event_inventory` | The single inventory bucket for one session. |
| `ticket_order` | Reservation order and payment-window lifecycle. |
| `ticket` | The issued ticket and the V1 one-user-one-session final guard. |

The baseline intentionally excludes waiting list, FLASH reservation, outbox,
consumer idempotency, notification, retry, DLQ, and compensation tables. Those
tables require later migrations when their corresponding behavior is implemented.

## Global Conventions

- MySQL 8.4 LTS with InnoDB and `utf8mb4`.
- Physical names use `snake_case`.
- Primary keys use `BIGINT AUTO_INCREMENT`.
- Logical reference columns use the same `BIGINT` type as their target primary key.
- Business states use `VARCHAR` plus `CHECK`, not MySQL `ENUM`. State columns use
  the case-sensitive `utf8mb4_0900_as_cs` collation so the checks enforce the
  frozen uppercase values exactly.
- Core tables use `created_at` and `updated_at` as UTC `DATETIME(6)` values.
- Relationships are logical foreign keys. The baseline does not add physical foreign
  keys in bulk; application rules, local transactions, constraints, and indexes
  maintain integrity.
- No soft-delete, optimistic-lock, tenant, distributed-ID, or speculative extension
  columns are included.

## Phase 5 V2 Verified Schema

V2 is limited to two columns required by the frozen User/Auth/Event contract:

| Table | Column | Type | Nullable | Default | Collation | Constraint | Index |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `users` | `role` | `VARCHAR(16)` | No | `USER` | `utf8mb4_0900_as_cs` | `CHECK (role IN ('USER', 'ADMIN'))` | None |
| `events` | `rejection_reason` | `VARCHAR(500)` | Yes | `NULL` | Inherit table `utf8mb4` collation | Cross-column status/reason check | None |

The implemented SQL shape for `users.role` is:

```sql
ALTER TABLE users
    ADD COLUMN role VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs
        NOT NULL DEFAULT 'USER' AFTER display_name,
    ADD CONSTRAINT ck_users_role CHECK (role IN ('USER', 'ADMIN'));
```

The default safely assigns `USER` to every existing row and to all normal registration
inserts that omit the column. No Admin seed user is created. Public registration must
never accept a role supplied by the client.

The implemented SQL shape for `events.rejection_reason` is:

```sql
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
```

MySQL 8.4 enforces this deterministic row-level `CHECK`. It is compatible with
expected-state conditional updates only when `status` and `rejection_reason` are
updated atomically in the same statement:

- reject sets `REJECTED` and the nonblank reason together;
- submit sets `PENDING_REVIEW` and clears the reason together;
- approve sets `PUBLISHED` and clears the reason together.

Before the verified V2 upgrade, the retained V1 database contained no existing User
or `REJECTED` Event rows, so no legacy-data decision or fabricated backfill was
required. Every independently upgraded environment must repeat that preflight; a
database containing `REJECTED` rows without truthful reasons must stop for an explicit
data decision. No index is added for either V2 column because no role-listing or
rejection-reason lookup query exists.

### V2 Runtime Verification

- Existing V1 was upgraded to V2 on MySQL 8.4.11 through Flyway.
- `flyway_schema_history` contains one successful V1 and one successful V2 record,
  with no failed migration.
- Both V2 checks are `ENFORCED`; default `USER`, explicit `ADMIN`, invalid lowercase
  role, rejection-reason rules, and atomic status/reason transitions were verified by
  real database behavior probes.
- The probes left no synthetic User or Event rows, and V2 introduced no index.
- A second focused run and the full Maven `clean verify` confirmed that V2 is not
  reapplied and the existing application baseline remains healthy.

## Phase 9 V3 Verified Schema

V3 is limited to extending the existing Order and Ticket status vocabularies and
preserving historical same-session purchase eligibility after Refund:

- `ticket_order.status` adds `REFUNDED`.
- generated `ticket_order.purchase_guard` yields `1` for `WAIT_PAY`, `PAID`, and
  `REFUNDED`, and `NULL` for `CLOSED` and `CANCELLED`.
- `ticket.status` adds `REFUNDED`.

V3 adds no table, business column, index, foreign key, or trigger. Real MySQL 8.4.11
verification applied V1, V2, then V3 from an empty schema; Flyway recorded three
successful migrations with no failure and reported the schema up to date on the
second run. Metadata and behavior probes verified both expanded status checks and
the generated purchase-guard behavior.

## Phase 10 V4 Verified Schema

V4 adds exactly one column and one named check to `event_sessions`:

| Column | Type | Nullable | Default | Collation | Constraint | Index |
| --- | --- | --- | --- | --- | --- | --- |
| `sale_mode` | `VARCHAR(16)` | No | `NORMAL` | `utf8mb4_0900_as_cs` | `ck_event_sessions_sale_mode`: `sale_mode IN ('NORMAL', 'FLASH')` | None |

The default safely upgrades existing Session rows to `NORMAL`. V4 adds no table,
index, foreign key, trigger, or other business column. Fresh MySQL 8.4.11 evidence
applied V1 through V4 with four successful history rows, zero failed migrations,
current version 4, and a second-run schema-up-to-date result. Real constraint behavior
proved the default, both uppercase values, case-sensitive rejection of lowercase and
other invalid values, and zero synthetic residue.

### Existing Username Compatibility

The V1 `username` definition is `VARCHAR(64) NOT NULL` plus
`UNIQUE(username)`. Its collation is inherited rather than explicitly pinned. A
case-insensitive inherited collation rejects case-only duplicates, but the Phase 5
contract does not depend on that environmental detail: registration and login both
perform `trim → lowercase`, validate `^[a-z0-9._-]{3,64}$`, and persist/query only the
canonical value. The existing unique key remains the concurrent-duplicate final guard.

V2 does not alter the username column and does not add a regex `CHECK`. Application
canonicalization plus the existing unique key is sufficient for supported writes and
avoids modifying a frozen field only for defensive duplication. V2 runtime
verification must nevertheless report any pre-existing noncanonical usernames before
Phase 5 business writes begin.

### Existing Password Compatibility

`password_hash VARCHAR(255)` is sufficient for Spring Security
`DelegatingPasswordEncoder` output using the `{bcrypt}` prefix. No schema change is
required. The 8–64 character rule and the bcrypt UTF-8 72-byte ceiling are input and
security-validation rules; they do not belong in a `password_hash` database check.

### Existing Event Status Compatibility

The V1 `events.status` column already uses case-sensitive
`utf8mb4_0900_as_cs` collation and a check covering exactly `DRAFT`,
`PENDING_REVIEW`, `PUBLISHED`, and `REJECTED`. V2 does not alter that column or its
existing state vocabulary.

## Detailed Schema

### `users`

| Column | Type | Nullable | Default | Constraint | Purpose |
| --- | --- | --- | --- | --- | --- |
| `id` | `BIGINT` | No | Auto increment | Primary key | Internal user identifier. |
| `username` | `VARCHAR(64)` | No | None | Unique | V1 login identifier. |
| `password_hash` | `VARCHAR(255)` | No | None | None | Password hash; never stores plaintext credentials. |
| `display_name` | `VARCHAR(100)` | Yes | `NULL` | None | Optional public display name. |
| `role` | `VARCHAR(16)` | No | `USER` | `CHECK (role IN ('USER', 'ADMIN'))` | Phase 5 authority persisted with case-sensitive V2 semantics. |
| `created_at` | `DATETIME(6)` | No | Current timestamp | None | Creation time. |
| `updated_at` | `DATETIME(6)` | No | Current timestamp | Updated on row change | Last update time. |

### `events`

| Column | Type | Nullable | Default | Constraint | Purpose |
| --- | --- | --- | --- | --- | --- |
| `id` | `BIGINT` | No | Auto increment | Primary key | Internal event identifier. |
| `organizer_user_id` | `BIGINT` | No | None | Logical reference to `users.id` | Event owner. |
| `title` | `VARCHAR(200)` | No | None | None | Event title. |
| `description` | `TEXT` | Yes | `NULL` | None | Optional event description. |
| `status` | `VARCHAR(32)` | No | `DRAFT` | Check: `DRAFT`, `PENDING_REVIEW`, `PUBLISHED`, `REJECTED` | Publication workflow state. |
| `rejection_reason` | `VARCHAR(500)` | Yes | `NULL` | Cross-column status/reason check | Required and nonblank only for `REJECTED`; otherwise `NULL`. |
| `created_at` | `DATETIME(6)` | No | Current timestamp | None | Creation time. |
| `updated_at` | `DATETIME(6)` | No | Current timestamp | Updated on row change | Last update time. |

### `event_sessions`

| Column | Type | Nullable | Default | Constraint | Purpose |
| --- | --- | --- | --- | --- | --- |
| `id` | `BIGINT` | No | Auto increment | Primary key | Internal session identifier. |
| `event_id` | `BIGINT` | No | None | Logical reference to `events.id` | Owning event. |
| `name` | `VARCHAR(200)` | No | None | None | Session name. |
| `venue` | `VARCHAR(255)` | Yes | `NULL` | None | Optional venue. |
| `sale_mode` | `VARCHAR(16)` | No | `NORMAL` | Check: `NORMAL`, `FLASH` | Reservation entry mode added by V4. |
| `sale_start_at` | `DATETIME(6)` | No | None | None | Ticket sale opening time. |
| `sale_end_at` | `DATETIME(6)` | No | None | Check: `sale_start_at < sale_end_at` | Ticket sale closing time. |
| `start_at` | `DATETIME(6)` | No | None | None | Session start time. |
| `end_at` | `DATETIME(6)` | No | None | Check: `start_at < end_at` | Session end time. |
| `created_at` | `DATETIME(6)` | No | Current timestamp | None | Creation time. |
| `updated_at` | `DATETIME(6)` | No | Current timestamp | Updated on row change | Last update time. |

### `event_inventory`

| Column | Type | Nullable | Default | Constraint | Purpose |
| --- | --- | --- | --- | --- | --- |
| `id` | `BIGINT` | No | Auto increment | Primary key | Internal inventory identifier. |
| `session_id` | `BIGINT` | No | None | Unique; logical reference to `event_sessions.id` | Enforces one inventory row per session. |
| `total_stock` | `INT` | No | None | Check: nonnegative | Fixed inventory total. |
| `available_stock` | `INT` | No | None | Check: nonnegative | Stock that can still be reserved. |
| `locked_stock` | `INT` | No | `0` | Check: nonnegative | Stock held by `WAIT_PAY` orders. |
| `sold_stock` | `INT` | No | `0` | Check: nonnegative | Stock converted by successful payment. |
| `created_at` | `DATETIME(6)` | No | Current timestamp | None | Creation time. |
| `updated_at` | `DATETIME(6)` | No | Current timestamp | Updated on row change | Last update time. |

The table also has a row-level check:

```text
total_stock = available_stock + locked_stock + sold_stock
```

Current NORMAL reservation uses one conditional update scoped by `session_id`
and `available_stock > 0` to atomically move one unit from available to locked.
The affected-row count determines whether the reservation won the competition.

### `ticket_order`

| Column | Type | Nullable | Default | Constraint | Purpose |
| --- | --- | --- | --- | --- | --- |
| `id` | `BIGINT` | No | Auto increment | Primary key | Internal order identifier. |
| `order_no` | `VARCHAR(64)` | No | None | Unique | Public business order number. |
| `user_id` | `BIGINT` | No | None | Logical reference to `users.id` | Ordering user. |
| `session_id` | `BIGINT` | No | None | Logical reference to `event_sessions.id` | Ordered session. |
| `status` | `VARCHAR(20)` | No | `WAIT_PAY` | Check: `WAIT_PAY`, `PAID`, `CLOSED`, `CANCELLED`, `REFUNDED` | Persisted Order state vocabulary through V3. |
| `expire_at` | `DATETIME(6)` | No | None | None | Payment deadline used by bounded timeout processing. |
| `purchase_guard` | `TINYINT` | Yes | Generated | Generated: `1` for `WAIT_PAY`/`PAID`/`REFUNDED`, otherwise `NULL` | Preserves the database guard against duplicate effective orders and repurchase after Refund. |
| `created_at` | `DATETIME(6)` | No | Current timestamp | None | Creation time. |
| `updated_at` | `DATETIME(6)` | No | Current timestamp | Updated on row change | Last update time. |

`purchase_guard` participates in a unique key over
`(user_id, session_id, purchase_guard)`. MySQL permits multiple `NULL` values in a
unique key, so multiple historical `CLOSED` or `CANCELLED` rows are allowed while
only one guarded `WAIT_PAY`, `PAID`, or `REFUNDED` row can exist for the same user
and session. Refund restores inventory but deliberately does not restore same-session
purchase eligibility.

No `expire_at > created_at` check is added. Deadline validity belongs to order
creation rules because database time expressions and application-supplied timestamps
would otherwise introduce avoidable coupling.

### `ticket`

| Column | Type | Nullable | Default | Constraint | Purpose |
| --- | --- | --- | --- | --- | --- |
| `id` | `BIGINT` | No | Auto increment | Primary key | Internal ticket identifier. |
| `ticket_no` | `VARCHAR(64)` | No | None | Unique | Public business ticket number. |
| `order_id` | `BIGINT` | No | None | Unique; logical reference to `ticket_order.id` | Enforces at most one ticket per order. |
| `user_id` | `BIGINT` | No | None | Part of unique key; logical reference to `users.id` | Ticket owner. |
| `session_id` | `BIGINT` | No | None | Part of unique key; logical reference to `event_sessions.id` | Ticketed session. |
| `status` | `VARCHAR(20)` | No | `VALID` | Check: `VALID`, `USED`, `REFUNDED` | Ticket lifecycle vocabulary through V3. |
| `created_at` | `DATETIME(6)` | No | Current timestamp | None | Creation time. |
| `updated_at` | `DATETIME(6)` | No | Current timestamp | Updated on row change | Last update time. |

`UNIQUE(user_id, session_id)` remains the database final guard for one ticket per
user per session. Phase 9 Refund changes a `VALID` Ticket to `REFUNDED` but does not
delete it, so the same-session purchase guard remains effective. Ticket transfer
remains undefined. Any later schema change requires a new migration and architecture
decision.

## Primary, Unique, and Check Constraints

| Table | Constraint | Business reason |
| --- | --- | --- |
| All six tables | Primary key on `id` | Stable internal identity using the frozen V1 key strategy. |
| `users` | Unique `username` | One account per login identifier. |
| `events` | Status check | Restricts persisted event workflow values. |
| `event_sessions` | Sale-mode and time-window checks | Restricts mode to `NORMAL` / `FLASH` and rejects reversed time ranges. |
| `event_inventory` | Unique `session_id` | One inventory bucket per session. |
| `event_inventory` | Four nonnegative checks | Prevents invalid negative stock. |
| `event_inventory` | Total-equals-components check | Enforces the inventory conservation invariant. |
| `ticket_order` | Unique `order_no` | One row per public order number. |
| `ticket_order` | Status check | Restricts the V3 Order vocabulary, including `REFUNDED`. |
| `ticket_order` | Unique `(user_id, session_id, purchase_guard)` | Prevents concurrent duplicate effective orders, preserves the guard after Refund, and permits retries only after close/cancel. |
| `ticket` | Unique `ticket_no` | One row per public ticket number. |
| `ticket` | Unique `order_id` | At most one ticket generated by an order. |
| `ticket` | Unique `(user_id, session_id)` | V1 final guard for one user and one session. |
| `ticket` | Status check | Restricts the V3 Ticket vocabulary to `VALID`, `USED`, and `REFUNDED`. |

## Business Invariant Mapping

| Business rule | Database support |
| --- | --- |
| No oversell in NORMAL mode | Atomic conditional update on `event_inventory` with `available_stock > 0`, checked through affected rows. |
| Inventory conservation | Nonnegative checks plus `total_stock = available_stock + locked_stock + sold_stock`. |
| Reservation success | In the current Order-owned Spring local transaction: insert `WAIT_PAY` Order, then call Inventory to move available-to-locked. |
| Payment success | In the Order-owned transaction, expected-state `WAIT_PAY → PAID` succeeds before Inventory locked-to-sold. |
| Timeout | In the Order-owned transaction, expected-state `WAIT_PAY → CLOSED` succeeds before Inventory locked-to-available. |
| Active cancellation | In the Order-owned transaction, expected-state `WAIT_PAY → CANCELLED` succeeds before Inventory locked-to-available. |
| Refund | In the Order-owned transaction, expected-state `PAID → REFUNDED` succeeds before Ticket `VALID → REFUNDED` and Inventory sold-to-available; Ticket and Inventory participate with `MANDATORY`. |
| One effective order per user/session | Generated `purchase_guard` unique key prevents concurrent guarded `WAIT_PAY`/`PAID`/`REFUNDED` duplicates. |
| One V1 ticket per user/session | Unique `(user_id, session_id)` on `ticket`. |
| Legal state vocabulary | `VARCHAR` status columns with checks; legal transitions still require expected-state conditional updates. |
| Cross-table atomicity | Spring local transaction over one MySQL datasource; no distributed transaction is needed for this baseline. |

Schema constraints do not replace domain rules. In particular, a status `CHECK`
cannot prove that a transition followed the state machine; later persistence code
must use expected-state conditional updates and verify affected rows.

## Deferred Design

- WaitingList persistence: `FUTURE / PLANNED`; exact table name, Schema, constraints and qualification lifecycle are not yet frozen.
- Current synchronous FLASH Reservation uses the existing six tables plus V4
  `event_sessions.sale_mode`; it does not add a persistent Reservation table. Future
  asynchronous Reservation, Outbox, RabbitMQ, and exact persistence/idempotency
  contracts remain deferred.
- Outbox persistence: Transactional Outbox direction is Future Frozen; exact table name, Schema and state machine are not yet frozen.
- Consumer idempotency storage: introduced with at-least-once message consumption.
- Notification records: introduced only if notifications require persistent delivery state.
- Retry, DLQ, compensation, and reconciliation tables: introduced from concrete failure-recovery requirements.
- `reservation_id`: not part of current Schema and not a frozen future field; the FLASH phase must freeze the persistence model and database safety line before migration.
- Financial refund settlement remains deferred: payment-provider integration, money
  transfer, provider callbacks, asynchronous MQ refund, notification, refund reason,
  partial refund, and forced/customer-service refund are not implemented.

## Freeze Decision

- Major Schema Ambiguity: `NO`.
- V1 design and runtime state: `FROZEN / IMPLEMENTED / VERIFIED`.
- V2 business scope: `FROZEN`.
- V2 exact schema state: `FROZEN / IMPLEMENTED / RUNTIME VERIFIED`.
- V2 scope is exactly `users.role` and `events.rejection_reason`; no other field,
  table, role model, or index is included.
- Phase 5 User, Auth, and Event implementations now consume the verified V2 schema;
  no additional Phase 5 schema migration was required.
- V3 exact schema state: `FROZEN / IMPLEMENTED / RUNTIME VERIFIED`.
- V3 changes only the Order/Ticket status checks and generated `purchase_guard`
  expression described above; it adds no table, business column, index, foreign key,
  or trigger.
- V4 exact schema state: `FROZEN / IMPLEMENTED / RUNTIME VERIFIED`.
- V4 adds only `event_sessions.sale_mode` and `ck_event_sessions_sale_mode`; it adds
  no table, index, foreign key, trigger, or other business column.

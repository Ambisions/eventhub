# EventHub V1 Baseline Indexes

Status: `V1 FROZEN / IMPLEMENTED / RUNTIME VERIFIED; V2 FROZEN / IMPLEMENTED / RUNTIME VERIFIED`

This document records the minimal index set for the six-table Phase 3 baseline.
Unique constraints create their own supporting indexes and must not be duplicated by
equivalent non-unique indexes.

| Table | Index | Columns | Query scenario |
| --- | --- | --- | --- |
| `users` | `uk_users_username` | `username` | Login lookup and username uniqueness. |
| `events` | `idx_events_organizer_created` | `organizer_user_id, created_at` | Organizer event list ordered or paged by creation time. |
| `events` | `idx_events_status_created` | `status, created_at` | Published/review event list by state and recency. |
| `event_sessions` | `idx_event_sessions_event_start` | `event_id, start_at` | Sessions for an event in chronological order. |
| `event_inventory` | `uk_event_inventory_session` | `session_id` | Inventory lookup and conditional update by session; also enforces 1:1. |
| `ticket_order` | `uk_ticket_order_order_no` | `order_no` | Public order lookup. |
| `ticket_order` | `uk_ticket_order_effective_purchase` | `user_id, session_id, purchase_guard` | Prevents concurrent duplicate effective orders. |
| `ticket_order` | `idx_ticket_order_user_created` | `user_id, created_at` | Paginated user order history. |
| `ticket_order` | `idx_ticket_order_session` | `session_id` | Logical session relationship and session-scoped order lookup. |
| `ticket_order` | `idx_ticket_order_status_expire` | `status, expire_at` | Scan expired `WAIT_PAY` orders without a broad full-table scan. |
| `ticket` | `uk_ticket_ticket_no` | `ticket_no` | Public ticket lookup. |
| `ticket` | `uk_ticket_order` | `order_id` | One ticket per order and order-to-ticket lookup. |
| `ticket` | `uk_ticket_user_session` | `user_id, session_id` | V1 one-user-one-session final guard and ownership lookup. |

## Explicitly Deferred

`ticket_order(session_id, status)` is not part of the baseline. The single-column
`session_id` index supports the logical relationship without anticipating a status
filter that has not yet been implemented. A later real query may replace or extend
this index after SQL shape and `EXPLAIN` evidence are available.

No waiting-list, FLASH reservation, outbox, consumer-idempotency, notification, or
message-reliability indexes are created before their tables and query paths exist.

## Phase 5 V2 Verified Index Decision

V2 adds no business index:

- `users.role`: no index. Phase 5 has no user-management or users-by-role query.
  Authorization reads the role with the account lookup already supported by
  `uk_users_username` or the primary key.
- `events.rejection_reason`: no index. The field is returned only as part of an Event
  record and is not a filter, join, ordering, or grouping key.

No existing V1 index is changed. Predictive role or rejection-reason indexes may be
considered only after a real query path and, where relevant, `EXPLAIN` evidence exist.

## Verification Boundary

The V1 index set has been verified against real MySQL metadata. Runtime metadata also
confirmed that V2 adds no index for `users.role` or `events.rejection_reason` and does
not change an existing V1 index. Future query-plan claims require the corresponding
SQL plus `EXPLAIN`; benchmark claims require measured evidence.

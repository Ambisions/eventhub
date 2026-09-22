# EventHub Development Progress

## Phase 0 — Repository Audit

Status: `COMPLETE`

- The repository initially consisted mainly of skeleton directories.
- The Root POM and Module POM files were initially empty.
- The existing Maven Wrapper files were initially invalid placeholders.
- No Spring Boot application existed.
- No infrastructure implementation existed.

## Phase 1 — Maven Multi-Module + Spring Boot Baseline

Status: `COMPLETE / VERIFIED / FROZEN`

### Maven Reactor

- Java 17.
- Spring Boot 3.5.16.
- Nine frozen Maven modules.
- The Root project is the Maven parent and aggregator.
- Maven `validate` completed with `BUILD SUCCESS`.

### Maven Wrapper

- Maven 3.9.16.
- Apache Maven Wrapper using the `only-script` distribution type.
- Windows Wrapper execution was verified.
- Unix executable verification: `DEFERRED`.

### Spring Boot Application

- `eventhub-app` is the only Spring Boot application.
- `eventhub-app` is the Composition Root.
- All other modules remain normal JAR modules.
- The Spring Context Smoke Test passed.
- Spring Boot packaging passed.
- `eventhub-app.jar` was generated.
- Real application startup passed with the embedded server.

### Current Architecture State

Implemented:

- Maven Multi-Module Reactor baseline.
- Reproducible Maven Wrapper baseline on Windows.
- Unique Spring Boot application and Composition Root baseline.
- Spring Context, packaging, and startup verification.

Not implemented:

- Business functionality.
- MySQL integration.
- Redis integration.
- RabbitMQ integration.
- Flyway migrations.
- NORMAL or FLASH reservation flows.

### Phase 1 Final Verification Follow-up

- Independent fallback audit runtime verification: `VERIFICATION SCRIPT HANG`; the run was interrupted after exceeding the expected time limit.
- A replacement runtime verification used a 45-second startup deadline, a 5-second HTTP timeout, and a bounded 5-second shutdown wait.
- The replacement verification passed in 12.587 seconds: the application started, HTTP 404 was observed, and no EventHub process or port 8080 listener remained.
- The formal Test Reviewer completed the independent evidence audit and reported `NO BLOCKING VERIFICATION GAP`.
- Unix `mvnw` executable verification remains `DEFERRED` until a Unix or CI environment is available.
- Phase 1 acceptance passed and the baseline is frozen.

## Agent Runtime Defect

Status: `ROOT CAUSE FOUND / FIXED / RE-VERIFIED`

- Reviewer and Test Reviewer model identifiers were changed from generic `gpt-5.6` to concrete Codex-supported `gpt-5.6-sol`.
- Real spawn probes for both formal roles completed successfully after the fix.

## Phase 2 — Docker Compose Infrastructure

Status: `COMPLETE / VERIFIED / FROZEN`

### Batch 1 — Compose Baseline

Status: `COMPLETE / VERIFIED / FROZEN`

- Docker Compose defines exactly three local infrastructure services: MySQL, Redis, and RabbitMQ.
- Images are pinned to `mysql:8.4.11`, `redis:8.2.9`, and `rabbitmq:4.3.5-management`.
- Each service uses a named volume and a service-specific healthcheck.
- Local development environment variables are documented in `.env.example`; real `.env` files remain ignored.
- Docker CLI, Docker Compose Plugin, and Docker Engine connectivity were verified.
- `docker compose config` completed with Exit Code 0, and the expected service and image sets were confirmed.
- `docker compose images` completed with Exit Code 0; the frozen images were not yet present locally, which does not affect Compose definition acceptance.

### Batch 2 — Runtime Infrastructure Verification

Status: `COMPLETE / VERIFIED`

- The frozen MySQL, Redis, and RabbitMQ images were pulled successfully.
- `docker compose up -d --wait --wait-timeout 120` completed successfully, and all three services became healthy.
- MySQL 8.4.11 authenticated successfully and executed read-only version, liveness, and current-database queries.
- Redis 8.2.9 returned `PONG` and reported the expected server version.
- RabbitMQ 4.3.5 passed diagnostics, reported a healthy node, and exposed the Management HTTP endpoint with status 200.
- The three Compose named volumes were created and verified.
- `docker compose down` removed all containers and released ports 3306, 6379, 5672, and 15672 while retaining the named volumes.
- Runtime verification produced no Repository artifacts; `.env` remained ignored and `git diff --check` passed.
- The formal Test Reviewer reported all 16 applicable properties as `PROVEN` with `NO BLOCKING VERIFICATION GAP`.

### Phase 2 Final Verification

Status: `COMPLETE / VERIFIED`

- Compose validation passed with exactly MySQL, Redis, and RabbitMQ using the frozen image versions.
- MySQL 8.4.11, Redis 8.2.9, and RabbitMQ 4.3.5-management all restarted successfully from the retained named volumes and became healthy.
- Minimal MySQL, Redis, and RabbitMQ protocol verification passed.
- RabbitMQ Management HTTP verification returned status 200.
- Named volume persistence across ordinary `docker compose down` was verified.
- Clean shutdown removed all containers and the default Compose network, released ports 3306, 6379, 5672, and 15672, and retained all three named volumes.
- Git hygiene verification passed, `.env` remained ignored and untracked, and no Docker runtime artifacts were created in the Repository.
- The formal Test Reviewer marked all 18 final verification properties as `PROVEN` and reported `NO BLOCKING VERIFICATION GAP`.

### Local Development Note

- This Windows development host has a separately installed `MySQL80` service with `AUTO_START` enabled.
- After a host restart, `MySQL80` may occupy port 3306 before EventHub Compose starts.
- This is a Local Environment Compatibility Note, not an EventHub Repository Defect.
- The Windows Service configuration was not modified.

## Phase 3 — Flyway Baseline Schema

Status: `COMPLETE / VERIFIED / FROZEN`

### Batch 1 — Database Schema Design

Status: `COMPLETE / FROZEN`

- The first database baseline is frozen at six tables: `users`, `events`,
  `event_sessions`, `event_inventory`, `ticket_order`, and `ticket`.
- Primary keys use `BIGINT AUTO_INCREMENT`; status fields use `VARCHAR + CHECK`;
  relationships use logical foreign keys with targeted indexes.
- The inventory nonnegative and conservation checks are retained.
- Duplicate effective orders are guarded by generated `purchase_guard` and a unique
  key over `user_id`, `session_id`, and `purchase_guard`.
- V1 ticket uniqueness is guarded by `UNIQUE(user_id, session_id)` without asserting
  a future refund or repurchase policy.
- Order timeout persists as `CLOSED`; the `ticket_order(session_id, status)` index is
  deferred, while `ticket_order(status, expire_at)` remains in the baseline.
- Major schema ambiguity: `NO`.
- Flyway migration implementation at the Batch 1 freeze point: `NOT STARTED`.

### Batch 2 — Flyway Migration Implementation

Status: `COMPLETE / STATICALLY VERIFIED / REVIEWED`

- `eventhub-app` now includes Spring JDBC, HikariCP, MySQL Connector/J, Flyway Core,
  and Flyway MySQL through Spring Boot 3.5.16 dependency management.
- The application Datasource uses local environment placeholders and is shared by
  Flyway; no database password is committed.
- `V1__baseline_schema.sql` implements exactly the six frozen baseline tables.
- Inventory conservation, generated `purchase_guard`, V1 ticket uniqueness, named
  state checks, and the frozen index set are implemented.
- State columns use a case-sensitive collation so database checks enforce the frozen
  uppercase values exactly.
- Dependency-tree verification and the reactor `package -DskipTests` build completed
  with `BUILD SUCCESS`.
- Formal Reviewer findings after the final fix: Blocker `0`, Major `0`, Minor `0`.
- Flyway runtime migration against MySQL 8.4.11: `VERIFIED` in Batch 3.

### Batch 3 — Real MySQL Migration Verification

Status: `COMPLETE / VERIFIED`

- Flyway applied `V1__baseline_schema.sql` successfully to real MySQL 8.4.11;
  the history contains exactly one successful V1 record.
- Runtime metadata verified six InnoDB/utf8mb4 business tables, the frozen CHECK and
  UNIQUE constraints, generated `purchase_guard`, and the frozen index set.
- Transaction-scoped synthetic probes proved inventory guards, case-sensitive status
  checks, effective-order uniqueness, historical closed/cancelled order reuse, and
  V1 ticket uniqueness; no probe data remained.
- The Datasource now explicitly fixes Connector/J and the MySQL session to UTC; a new
  JDBC connection reported `@@session.time_zone = +00:00` while the JVM default was
  `Asia/Shanghai`.
- A second Spring Context run reported schema version 1 as current with no migration
  necessary, and the V1 history record remained singular.
- The full Maven Reactor `clean verify` completed with `BUILD SUCCESS` and the Context
  Smoke Test passed against the real database.
- Normal Compose shutdown removed the MySQL container and network, released port 3306,
  and retained `eventhub_mysql-data` with the migrated baseline schema.
- Formal UTC configuration re-review reported Blocker `0`, Major `0`, Minor `0`.
- The formal Test Reviewer marked all 25 Batch 3 runtime-evidence properties as
  `PROVEN`, required no additional runs, and reported `NO BLOCKING VERIFICATION GAP`.
- Flyway's MySQL support-range warning is retained as a future compatibility note;
  the pinned versions and V1 migration behavior passed the current real runtime checks.

### Phase 3 Final Verification

Status: `COMPLETE / VERIFIED / FROZEN`

- MySQL 8.4.11 restarted successfully from the retained `eventhub_mysql-data` volume.
- The persisted Flyway history remained one successful V1 record, and all six baseline
  business tables remained present before and after the final build.
- Flyway validated the existing schema and reported no migration necessary; V1 was not
  re-applied.
- The full Root + 9 Module Maven Reactor `clean verify` completed with `BUILD SUCCESS`,
  the Spring Context test passed, and Boot repackage completed.
- A fresh Connector/J connection confirmed `@@session.time_zone = +00:00` while the JVM
  default remained `Asia/Shanghai`.
- Clean shutdown removed the MySQL container and Compose network, released port 3306,
  and retained the migrated named volume.
- Flyway 11.7.2's MySQL support-range warning remains a Known Compatibility Note and
  must be re-evaluated when Spring Boot, Flyway, or MySQL is upgraded.

## Phase 4 — Persistence & Infrastructure Integration Baseline

Status: `COMPLETE / VERIFIED / FROZEN`

### Batch 1 — MyBatis Persistence Baseline

Status: `COMPLETE / VERIFIED`

- MyBatis-Plus 3.5.17 is managed centrally through the Root MyBatis-Plus BOM.
- `eventhub-app` owns the Spring Boot 3 MyBatis-Plus runtime bootstrap and the single,
  targeted Mapper scan for `com.eventhub.user.mapper`.
- `eventhub-user` owns `UserEntity` and `UserMapper` and depends only on the
  MyBatis-Plus Core API; `eventhub-common` remains free of persistence framework
  dependencies.
- The first persistence smoke path verified Java → MyBatis-Plus → HikariCP → real
  MySQL 8.4.11 against the Flyway V1 `users` table.
- The integration test verified insert affected rows, generated `AUTO_INCREMENT` ID,
  `selectById`, snake_case-to-camelCase mapping, and `DATETIME(6)` mapping.
- Spring test transaction rollback was independently verified twice with zero
  synthetic-user residue.
- Focused Maven tests and the full Root + 9 Module `clean verify` completed with
  `BUILD SUCCESS`; the existing Spring Context test and the new persistence test
  both passed.
- The formal Reviewer reported Blocker `0`, Major `0`, Minor `0`, and Needs
  Verification `0`.
- The formal Test Reviewer reported no blocking verification gap.
- Mapper XML runtime proof remains deferred until the first real conditional-update
  use case; no placeholder XML was added.
- Redis client integration is implemented and verified in Batch 2.
- RabbitMQ client integration is implemented and verified in Batch 3.

### Batch 2 — Redis Client Baseline

Status: `COMPLETE / VERIFIED`

- `eventhub-app` owns the Spring Data Redis runtime dependency, with versions managed
  by Spring Boot 3.5.16; the resolved client is Lettuce and no Jedis, Redisson, or
  `commons-pool2` dependency was introduced.
- Redis uses Boot auto-configuration with environment-overridable host and port,
  database 0, and 2-second connect and command timeouts; no custom connection factory,
  template, serializer, pool, authentication, or cache configuration was added.
- A real `StringRedisTemplate` integration test against Redis 8.2.9 verified
  SET, GET, positive TTL, DELETE, and absence after deletion using an isolated UUID key.
- Exact-key `finally` cleanup and two independent SCAN checks verified zero
  `eventhub:test:redis-smoke:*` residue after focused tests and full verification.
- MySQL 8.4.11 and Redis 8.2.9 were the only runtime services started; RabbitMQ was
  not started and no FLASH, Lua, distributed-lock, inventory-key, or business Redis
  contract was introduced.
- Focused Reactor tests and the full Root + 9 Module `clean verify` completed with
  `BUILD SUCCESS`; all three integration tests passed with no failures or errors.
- Normal Compose shutdown removed the MySQL and Redis containers and network,
  released ports 3306 and 6379, and retained both named volumes.
- The formal Reviewer reported Blocker `0`, Major `0`, Minor `0`, and Needs
  Verification `0`.
- The formal Test Reviewer marked all 16 evidence properties as `PROVEN` and reported
  `NO BLOCKING VERIFICATION GAP`; Spring Boot's conditional default generic template
  remains framework auto-configuration and is not an EventHub-defined or used Redis
  contract.
- RabbitMQ client integration is implemented and verified in Batch 3.

### Batch 3 — RabbitMQ Client Baseline

Status: `COMPLETE / VERIFIED`

- `eventhub-app` owns the Spring Boot AMQP Starter, with Spring Boot 3.5.16 managing
  Spring Rabbit and Spring AMQP 3.2.12 plus RabbitMQ Java Client 5.25.0; no other MQ
  client dependency was introduced.
- RabbitMQ uses Boot default auto-configuration with environment-sourced Local
  Development credentials, local host and port defaults, and a 5-second connection
  timeout; no custom connection factory, template, admin, or message converter exists.
- A real `RabbitTemplate.execute` integration test against RabbitMQ 4.3.5 verified the
  Boot-managed `CachingConnectionFactory`, AMQP authentication, open Connection and
  Channel, and type-safe RabbitMQ product/version metadata.
- The connectivity test did not send or receive messages and did not define any
  Exchange, Queue, Binding, Routing Key, Listener, Producer, Consumer, or message
  contract.
- Focused Reactor tests and the full Root + 9 Module `clean verify` completed with
  `BUILD SUCCESS`; all four integration tests passed with no failures or errors, and
  the existing Flyway/MyBatis and Redis baselines did not regress.
- Two independent checks found zero EventHub-created Queue, Exchange, or Binding and
  zero application AMQP connections after each Maven process; the full verification
  also left zero Redis smoke-test keys.
- Normal Compose shutdown removed all three containers and the default network,
  released ports 3306, 6379, 5672, and 15672, and retained all three named volumes.
- The formal Reviewer reported Blocker `0`, Major `0`, Minor `0`, and Needs
  Verification `0`.
- The formal Test Reviewer marked all 20 evidence properties as `PROVEN`, required no
  additional run, and reported `NO BLOCKING VERIFICATION GAP`.
- Messaging reliability features including Outbox, Publisher Confirm/Return, Manual
  ACK, Retry, DLQ, idempotent consumption, and compensation remain `NOT IMPLEMENTED`.
- Batch 4 completed the integrated Phase-level verification described below.

### Batch 4 — Integrated Infrastructure Final Verification

Status: `COMPLETE / VERIFIED`

- MySQL 8.4.11, Redis 8.2.9, and RabbitMQ 4.3.5-management restarted together
  from the retained named volumes and all three services became healthy.
- The full Root + 9 Module `clean verify` completed with `BUILD SUCCESS`; all four
  application integration tests passed with no failures, errors, or skipped tests,
  and Spring Boot repackage completed successfully.
- Flyway validated the existing V1 schema without re-applying it; the history remained
  one successful V1 record, no failed migration existed, and the six business tables
  remained present. The frozen V1 migration checksum evidence remained unchanged.
- The MyBatis persistence test retained correct insert/select mapping and transaction
  rollback behavior, leaving zero `phase4-smoke-*` users.
- The Redis client test retained SET/GET/TTL/DELETE behavior and left zero
  `eventhub:test:redis-smoke:*` keys.
- The RabbitMQ client test retained authentication, Connection, Channel, and server
  metadata behavior; no EventHub Queue, Exchange, Binding, or application AMQP
  connection remained after the Maven lifecycle.
- No blocking conflict was found between MyBatis and Flyway, Redis and the Datasource,
  RabbitMQ and Redis auto-configuration, Spring Boot dependency management, Spring
  beans, configuration namespaces, or integration-test lifecycles.
- Normal Compose shutdown removed all containers and the default network, released
  ports 3306, 6379, 5672, and 15672, and retained all three named volumes.
- The formal Test Reviewer marked the MyBatis, Redis, RabbitMQ connectivity, and
  cross-infrastructure coexistence claims as `PROVEN`, required no additional runs,
  and reported `NO BLOCKING VERIFICATION GAP`.
- Flyway/MySQL support-range warning, local `MySQL80` AUTO_START port behavior, and
  RabbitMQ persistent-volume credential behavior remain documented compatibility and
  operations notes; none is a current Repository defect or blocking gap.
- Phase 4 proves only the integrated Infrastructure Client Baseline. FLASH, messaging
  reliability, and cross-infrastructure eventual-consistency workflows remain
  `NOT IMPLEMENTED`.

## Phase 5

Status: `COMPLETE / VERIFIED / FROZEN`

### Batch 1A — Contract / Source of Truth Freeze

Status: `COMPLETE / CONTRACT FROZEN / V2 DESIGN FROZEN`

- Authority 冻结为 `USER / ADMIN`；Organizer 使用 Event Ownership，不是全局 Role。
- Username canonicalization、Password 安全边界、Access Token Only JWT 与 Stateless Client Logout Contract 已冻结。
- Event Ownership、状态机、编辑规则、Reject Reason 与 Public Visibility 已冻结。
- `eventhub-common` 最小边界与 User / Auth / Event dependency direction 已冻结。
- V2 业务范围精确限定为 `users.role` 与 `events.rejection_reason`；Frozen Schema Design 不新增业务 Index。
- Phase 3 Schema 文档中的旧 Runtime 状态已同步为 `VERIFIED / FROZEN`。
- 本 Batch 未修改 Java Source、POM 或 Flyway Migration，未执行 Runtime Migration。

### Batch 1B — V2 Migration Implementation + Real MySQL Verification

Status: `COMPLETE / VERIFIED`

- Added `V2__add_user_role_and_event_rejection_reason.sql` without modifying the
  historical V1 migration.
- Existing V1 Flyway history and data safety were checked before migration; the
  retained database had no User or `REJECTED` Event rows requiring a legacy-data
  decision.
- Real MySQL 8.4.11 upgrade, V2 Flyway history, column metadata, enforced checks, and
  no-index scope were verified.
- Role default/case sensitivity, rejection-reason invariants, and atomic status/reason
  transitions passed real database behavior probes with zero synthetic residue.
- Second-run Flyway stability and the full Maven `clean verify` passed with four tests
  and all ten Reactor projects successful.
- Formal Reviewer reported no Blocker, Major, or Minor finding. Formal Test Reviewer
  found no blocking verification gap.

### Batch 2 — User Foundation

Status: `COMPLETE / VERIFIED`

- `eventhub-user` now exposes separate account/profile and credential-query public
  APIs; persistence entities and mappers remain internal to the module.
- Username identity uses one domain-owned `trim -> lowercase(Locale.ROOT) -> validate`
  policy for account creation and credential lookup.
- Account creation always writes `UserRole.USER`; MyBatis/MyBatis-Plus default enum
  mapping for both `USER` and `ADMIN` was verified against the Flyway V2 MySQL schema.
- Profile models exclude credentials, authentication data redacts the encoded password
  from string output, and account-creation commands cannot specify a role or raw password.
- Canonical duplicate usernames are rejected by `uk_users_username` and translated to
  `USERNAME_ALREADY_EXISTS` without a pre-insert existence check.
- Display names support trimmed 1-100 character values and explicit `NULL` clearing.
- Focused unit tests, real MySQL integration tests, transaction rollback residue checks,
  and the full Maven `clean verify` passed. No User Controller, Spring Security, JWT, or
  Auth implementation was introduced.

### Batch 3 — Auth / Security / JWT

Status: `COMPLETE / VERIFIED`

- `eventhub-auth` now owns registration, login, the frozen raw-password policy,
  DelegatingPasswordEncoder integration, uniform invalid-credential handling, and
  access-token issuance through the Spring Security `JwtEncoder` abstraction.
- `eventhub-app` owns strict environment-backed JWT key bootstrap, the HS256-only
  Nimbus encoder/decoder, required-claim validation, trusted principal conversion,
  the stateless SecurityFilterChain, JSON 401/403 handling, and Auth/User HTTP APIs.
- Issued access tokens contain only the frozen `sub`, `username`, `role`, `iat`,
  `exp`, and `iss=eventhub` claims with an exact 30-minute TTL. Production has no
  signing-secret fallback; the deterministic test key is isolated in the test profile.
- Registration and login passed against real MySQL 8.4.11 with Flyway V2, including
  canonical usernames, bcrypt hashes, `USER` role enforcement, duplicate handling,
  uniform authentication failures, and zero synthetic-user residue.
- MockMvc tests using the real Spring Security filter chain verified anonymous
  register/login, trusted JWT identity, role mapping, structured 401/403 responses,
  rejection of invalid tokens, and stateless logout where the same valid token remains
  usable until expiration.
- The full Root + 9 Module `clean verify` completed with `BUILD SUCCESS`: 52 tests
  passed with zero failures, errors, or skipped tests; Flyway V2, MyBatis, Redis,
  RabbitMQ, and Boot repackage baselines did not regress.
- Runtime checks found no Auth/User rows, Redis token-session keys, RabbitMQ Auth
  topology, or application AMQP connection residue.
- The formal Reviewer reported Blocker `0`, Major `0`, Minor `0`, and Needs
  Verification `0`. The formal Test Reviewer marked all 27 audited properties as
  `PROVEN` and reported `NO BLOCKING VERIFICATION GAP`.
- Phase 5 remains `IN PROGRESS`.

### Batch 4 — Event / EventSession

Status: `COMPLETE / VERIFIED / FROZEN`

- `eventhub-event` now owns Event and EventSession public APIs, domain input/state
  policies, persistence models, MyBatis mappers/XML, and local transaction logic;
  HTTP controllers and Security composition remain in `eventhub-app`.
- Event creation derives ownership from the authenticated principal and starts in
  `DRAFT`. Owner editing, submit/resubmit, Admin approve/reject, and atomic
  `rejection_reason` maintenance follow the frozen state machine.
- Public Event and Session queries enforce `PUBLISHED` visibility in SQL. The public
  Event detail response is identity-invariant for anonymous, Owner, and Admin callers;
  Organizer and Admin management views use separate endpoints.
- Session create/update and Event submit serialize on the same Parent Event row.
  Deterministic real-MySQL tests verified both lock orders, and expected-state SQL
  verified single-winner submit and approve/reject behavior.
- Bounded `size + 1` pagination, HTTP defaults, no-total response shape, validation,
  ownership masking, and 400/401/403/404/409 error translation were verified.
- Final Root + 9 Module `clean verify` completed with `BUILD SUCCESS`: 80 tests passed
  with zero failures, errors, or skipped tests. Targeted evidence additions passed
  10/10 without changing Production Code.
- MySQL Event/Session/User residue, Redis Event/Session/Smoke keys, RabbitMQ EventHub
  topology, and application AMQP connections were all zero after verification.
- Formal Reviewer final findings were all zero. Formal Test Reviewer marked all 18
  audited properties `PROVEN` and reported `NO BLOCKING VERIFICATION GAP`.
- No V3 migration, Redis Event business logic, RabbitMQ Event messaging, Inventory,
  Order, or Ticket implementation was introduced; historical V1/V2 hashes remain
  unchanged.

### Batch 5 — Integrated Verification / Phase Freeze

Status: `COMPLETE / VERIFIED / FROZEN`

- The final Root + 9 Module `clean verify` completed with `BUILD SUCCESS`: 80 tests
  passed with zero failures, errors, or skipped tests, and Spring Boot repackage passed.
- User, Auth/Security/JWT, Event, and EventSession capabilities ran in the same
  `EventHubApplication` Composition Root with Flyway V2, MyBatis, Redis, and RabbitMQ.
- Flyway history remained at two successful migrations with current version 2; all
  six business tables and all ten enforced CHECK constraints remained stable.
- Final checks found zero synthetic User/Auth/Event/Session rows, zero Redis smoke or
  Phase 5 business keys, zero EventHub-created RabbitMQ topology, and zero application
  AMQP connections after Maven exited.
- Migration hashes remained frozen, no V3 migration was introduced, and no Inventory,
  Order, Ticket, FLASH, Redis business, or RabbitMQ business implementation was added.
- The formal Test Reviewer marked all Phase-level acceptance areas as `PROVEN`, found
  no blocking verification gap, and recommended Phase 5 freeze approval.

## Phase 6

Status: `COMPLETE / VERIFIED / FROZEN`

### Batch 1 — Event Guard + Inventory Foundation

Status: `COMPLETE / VERIFIED / FROZEN`

- Added the Event public session guard and joined its Parent Event `SELECT ... FOR UPDATE`
  to the Inventory configuration transaction, preserving ownership hiding and editable-state rules.
- Added Inventory management and reservation APIs, V1 entity mapping, MyBatis XML conditional
  updates, invariant-preserving lock/sell/release transitions, and same-value configuration handling.
- Added the authenticated Organizer Inventory HTTP boundary; organizer identity comes only from
  the JWT principal and no Organizer role or client-supplied user identity was introduced.
- Module, HTTP, and real MySQL tests passed, including deterministic Inventory configuration versus
  Event submit lock-order scenarios with bounded coordination and zero synthetic residue.
- Formal Reviewer reported Blocker `0`, Major `0`, Minor `0`; one non-blocking future verification
  note remains for directly exercising the defensive duplicate-key classification branch.
- No Schema migration, Order implementation, Redis business logic, or RabbitMQ business logic was added.

### Batch 2 — NORMAL Reservation + WAIT_PAY Order

Status: `COMPLETE / VERIFIED / FROZEN`

- Added `ReservationApi`, the `WAIT_PAY` Order persistence path, deterministic UTC time handling,
  the 15-minute payment window, and the authenticated `POST /api/sessions/{sessionId}/orders` boundary.
- `OrderService.reserve()` owns one local transaction and executes `INSERT WAIT_PAY` before the
  Inventory conditional lock; real MySQL tests proved both success atomicity and rollback for
  `SOLD_OUT` / `INVENTORY_NOT_CONFIGURED`.
- The generated `purchase_guard` unique constraint remains the final active-purchase safety line;
  no active-order precheck was added, and exact duplicate-constraint translation was verified.
- Real MySQL contention proved exactly one success for 100 users competing for stock 1 and exactly
  one active Order / one inventory lock for ten concurrent requests from the same user.
- Targeted tests and the final Root + 9 Module `clean verify` passed with zero failures, errors, or
  skipped tests; formal Reviewer and Test Reviewer reported no blocking gap.
- V1/V2 migrations remain unchanged, no V3 was added, and Pay, Cancel, Timeout, Ticket, Redis
  business logic, and RabbitMQ business messaging remain not started.

### Batch 3 — Pay + Cancel + Order Query

Status: `COMPLETE / VERIFIED / FROZEN`

- Added authenticated owner-only Order query plus synchronous Pay and active Cancel APIs; missing
  and other-owner Orders are both exposed as `ORDER_NOT_FOUND`.
- Pay uses one UTC microsecond snapshot and an owner-scoped Conditional UPDATE to perform only
  unexpired `WAIT_PAY -> PAID`; Cancel performs only `WAIT_PAY -> CANCELLED` and remains available
  for expired `WAIT_PAY` Orders.
- Order and Inventory updates share one Local Transaction with a fixed `Order -> Inventory` write
  order. The Order affected-row result gates the single Inventory `locked -> sold` or
  `locked -> available` mutation.
- Real MySQL tests proved exact-expiry rejection, repeat-call safety, cross-table rollback, terminal
  state rejection, owner hiding, Inventory conservation, and a bounded two-thread Pay-vs-Cancel
  race with exactly one winner and exactly one Inventory side effect.
- Targeted Maven tests and the final Root + 9 Module `clean verify` completed with `BUILD SUCCESS`;
  all tests passed with zero failures, errors, or skipped tests, and Boot repackage succeeded.
- Formal Reviewer reported Blocker `0`, Major `0`, Minor `0`, and Needs Verification `0`. Formal
  Test Reviewer marked all 15 required properties `PROVEN` with no blocking verification gap.
- V1/V2 migrations remain unchanged, no V3 was added, and Ticket, Refund, payment-provider,
  FLASH, Redis business, and RabbitMQ business capabilities remain not started.

### Batch 4 — Timeout Scanner

Status: `COMPLETE / VERIFIED / FROZEN`

- Added a database-backed, bounded `WAIT_PAY` timeout scanner using one UTC microsecond
  `scanNow`, stable `expire_at ASC, id ASC` candidate ordering, and configurable batch size.
- Each candidate runs in an independent `READ_COMMITTED` Local Transaction and uses a
  Conditional UPDATE for `WAIT_PAY -> CLOSED`; only the successful Order transition releases
  one Inventory unit from `locked_stock` back to `available_stock`.
- The scanner continues after an isolated candidate failure, while a release failure rolls back
  that Order transition. Scheduler execution uses `fixedDelay` and is disabled in the test profile.
- Real MySQL tests proved past/exact/future expiry behavior, repeated-run idempotency, rollback,
  failure isolation, bounded stable ordering, and Timeout-vs-Timeout / Cancel / Pay races.
- Targeted Maven tests and the final Root + 9 Module `clean verify` completed with `BUILD SUCCESS`;
  all tests passed with zero failures, errors, or skipped tests, and Boot repackage succeeded.
- Formal Reviewer reported Blocker `0`, Major `0`, Minor `0`, and Needs Verification `0`. Formal
  Test Reviewer marked all 14 required properties `PROVEN` with no blocking verification gap.
- V1/V2 migrations remain unchanged, no V3 was added, and Ticket, Refund, payment-provider,
  FLASH, Redis business, RabbitMQ business, and asynchronous timeout capabilities remain deferred.

### Batch 5 — Integrated Verification / Final Freeze

Status: `COMPLETE / VERIFIED / FROZEN`

- Closed the two remaining test-only evidence gaps with real MySQL cases for Inventory
  configuration on a `REJECTED` Event and Pay after `sale_end_at` but before Order expiry;
  no Production Code was changed.
- A fresh isolated Compose project started from zero domain tables, and Flyway applied V1 then
  V2 successfully. The history remained two successful migrations on subsequent application runs.
- Runtime metadata and behavior checks verified the six-table schema, enforced Inventory and
  Order constraints, generated `purchase_guard`, frozen indexes, active-order rejection, and
  terminal-order guard release with zero probe residue.
- The fresh Phase 6 targeted suite passed 64 tests, including real MySQL no-oversell,
  same-user active-purchase, Submit/configuration, Pay/Cancel, and all Timeout races plus the
  four cross-table rollback paths.
- The final Root + 9 Module `clean verify` completed with `BUILD SUCCESS`: 146 tests passed
  with zero failures, errors, or skipped tests, and Spring Boot repackage succeeded.
- Final database, Redis, RabbitMQ topology, and AMQP connection residue checks were all zero;
  Inventory nonnegative and conservation audits passed.
- The formal Reviewer reported Blocker `0`, Major `0`, Minor `0`, and Needs Verification `0`.
  The Phase-level Test Reviewer marked all 26 audited properties `PROVEN` and reported
  `NO BLOCKING VERIFICATION GAP`.
- V1/V2 migrations, Future Contract, and Governance remain unchanged; Phase 6 is now frozen.

### Global Governance Reconciliation

Status: `COMPLETE`

- Global Architecture & Governance Audit: `COMPLETE`; Decision A preserves Production Code,
  the Phase 6 frozen runtime contract, and the Root architecture philosophy.
- Future Contract Provenance Precheck: `COMPLETE`; lifecycle, decision, and implementation
  status are recorded independently so `FUTURE / FROZEN / NOT_IMPLEMENTED` remains valid.
- Minimal Governance Reconciliation aligns the Root/Nested AGENTS, affected Skills/References,
  Specification, and Schema with the current Phase 6 Order-owned NORMAL lifecycle exception.
- Current NORMAL remains `Order -> Inventory`; Timeout persists `CLOSED`. Refund, FLASH,
  Transactional Outbox, At-Least-Once + Idempotent Consumer, Fail Fast, and Ticket issuance
  retain their approved Future Frozen direction without being described as implemented.
- Refund ownership/API/Ticket refund state, Reservation persistence / `reservation_id`,
  Timeout MQ mechanism, WaitingList implementation, Notification topology, exact Outbox Schema,
  and exact MQ idempotency Key remain unfrozen or Planned as applicable.
- No Production Code, Test, POM, Migration, Docker, or runtime configuration was changed.
  Current factual status: Phase 6 is `COMPLETE / VERIFIED / FROZEN` (see Batch 5 above).

## Phase 7 — Ticket Foundation

Status: `COMPLETE / VERIFIED / FROZEN`

### Batch 1 — Ticket Contract Freeze

Status: `COMPLETE / FROZEN`

### Batch 2 — Ticket Persistence Foundation

Status: `COMPLETE / VERIFIED / FROZEN`

- Established Ticket public APIs, MyBatis persistence, V1 uniqueness and status guards,
  `MANDATORY` issuance transaction participation, and owner-scoped query capability.

### Batch 3 — Payment-to-Ticket Integration

Status: `COMPLETE / VERIFIED / FROZEN`

- Pay now issues one VALID Ticket synchronously after Order and Inventory writes in the
  same Order-owned Local MySQL Transaction; success returns `PaymentResult(OrderView, TicketView)`.

### Batch 4 — Owner-only Ticket Query

Status: `COMPLETE / VERIFIED / FROZEN`

- Added authenticated `GET /api/tickets/{ticketNo}` with owner-only visibility and
  identical 404 handling for missing, invalid, and other-owner ticket numbers.

### Batch 5 — Integrated Verification / Final Freeze

Status: `COMPLETE / VERIFIED / FROZEN`

- Fresh isolated Compose project `eventhub-phase7-batch5-20260922-01` started MySQL
  8.4.11 with zero pre-migration business tables. Flyway V1 then V2 succeeded;
  history remained two successful migrations with zero failures, and a second run
  reported the schema up to date.
- Phase 6/7 targeted verification passed 50 tests with zero failures, errors, or
  skips. The final Root + 9 Module `clean verify` passed 42 suites / 175 tests
  with zero failures, errors, or skips; Spring Boot repackage succeeded.
- Ticket constraint, concurrency, three-table atomicity, owner-only Web/Security,
  Phase 6 regression, and independent synthetic-data/invariant residue audits passed.
  Formal Reviewer and Phase-level Test Reviewer both passed with no blocking gap.
- Windows host note: a host-specific Maven Wrapper path issue required a temporary
  user-TEMP junction to the existing Maven home for verification. It was removed;
  the safety recheck found no persistent repository, PATH, environment, or `.m2` side effects.

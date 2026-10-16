# EventHub

[简体中文](README.md) | [English](README_EN.md)

> High-Concurrency Event Booking & Ticketing Platform

![Java 17](https://img.shields.io/badge/Java-17-007396?logo=openjdk&logoColor=white)
![Spring Boot 3.5.16](https://img.shields.io/badge/Spring%20Boot-3.5.16-6DB33F?logo=springboot&logoColor=white)
![MySQL 8.4](https://img.shields.io/badge/MySQL-8.4-4479A1?logo=mysql&logoColor=white)
![Redis 8.2](https://img.shields.io/badge/Redis-8.2-DC382D?logo=redis&logoColor=white)
![RabbitMQ 4.3](https://img.shields.io/badge/RabbitMQ-4.3-FF6600?logo=rabbitmq&logoColor=white)
![License Apache-2.0](https://img.shields.io/badge/License-Apache--2.0-D22128)

EventHub is a Java modular monolith running in a single JVM. It is designed around inventory correctness, order lifecycles, FLASH admission control, and asynchronous notifications. MySQL holds authoritative business state, Redis Lua handles FLASH admission, and RabbitMQ provides a post-commit best-effort notification foundation. High concurrency is an architectural objective, not an unverified performance claim.

**Core highlights**

- MySQL conditional updates and database constraints prevent overselling, inventory drift, and duplicate active purchases.
- NORMAL and FLASH reservation paths share the same authoritative MySQL order and inventory model.
- Order, Inventory, and Ticket participate in one local transaction during payment.
- The RabbitMQ notification projection is idempotent while retaining an explicit best-effort delivery boundary.

**Quick navigation**: [Architecture](docs/architecture/overview.md) · [Core flows](docs/architecture/core-flows.md) · [Local setup](docs/deployment/local-development.md) · [HTTP API](docs/api/http-api.md)

## 1. Project Overview & Goals

EventHub targets booking scenarios such as technical conferences, meetups, exhibitions, and performances. Its focus is to demonstrate how business correctness evolves from straightforward database transactions to Redis and RabbitMQ, rather than accumulating middleware for its own sake.

- **Architecture**: a Java modular monolith; `eventhub-app` is the only Composition Root, and all modules run in one JVM.
- **Module collaboration**: controllers and cross-domain orchestration live in `eventhub-app` by default. Reserve, Pay, Cancel, Timeout, and Refund are frozen narrow exceptions transactionally orchestrated by `eventhub-order`, with participating modules still joining only through public `api` packages.
- **Data responsibilities**: MySQL is the Source of Truth; Redis stores FLASH admission/execution state; RabbitMQ transports notifications.
- **Engineering goals**: business correctness, data consistency, idempotency, explicit failure boundaries, verifiability, and explainable architectural evolution.

The only global roles are `USER` and `ADMIN`. Organizer denotes ownership of an Event resource; it is not a third global role.

## 2. Features & Current Status

| Status | Capabilities |
| --- | --- |
| **IMPLEMENTED** | Registration/login, JWT authentication, Event/Session management, organizer ownership, inventory configuration, NORMAL/FLASH reservation, the `WAIT_PAY` lifecycle, payment/cancellation/timeout, refund, and Ticket issue/query/check-in |
| **FOUNDATION IMPLEMENTED** | `TicketIssuedEvent`, post-commit best-effort RabbitMQ publishing, durable direct topology, a consumer-local transaction, `user_notification` persistence, and duplicate-message idempotency |
| **PLANNED / DEFERRED** | Transactional Outbox, Publisher Confirm/Return, Retry/DLQ, automatic reconciliation, benchmarks, and CI |

Current boundaries:

- Notification delivery is not guaranteed. There is no Outbox, Publisher Confirm/Return, Retry/DLQ, or notification query API.
- FLASH executes `Redis Lua admission → synchronous MySQL reservation`; it has no Redis queue, asynchronous order creation, or atomic Redis/MySQL commit.
- Post-commit FLASH synchronization to Redis is best-effort, with no automatic reconciliation or Redis crash recovery.

## 3. Architecture

```text
Client
  → eventhub-app (HTTP, Security, Application Services, Composition Root)
    → in-process public APIs
      → user / auth / event / inventory / order / ticket / notification modules
        → MySQL (authoritative state)
        → Redis (FLASH admission and execution state)
        → RabbitMQ (best-effort notification transport)
```

This is a modular monolith deployed as one unit in one JVM, not a microservice system. See the [architecture overview](docs/architecture/overview.md) for module boundaries, transaction ownership, and infrastructure responsibilities.

## 4. Core Business Flows

| Flow | Current implementation | Key boundary |
| --- | --- | --- |
| **NORMAL** | Conditionally decrement MySQL inventory and create a `WAIT_PAY` Order in a local transaction | A hot Session can still contend on the database inventory row |
| **FLASH** | Perform Redis Lua admission, synchronously reserve in MySQL, then settle Redis on a best-effort basis | Redis Lua and the MySQL commit do not form an atomic distributed transaction |
| **Payment** | Commit Order `WAIT_PAY → PAID`, Inventory `locked → sold`, and Ticket issuance in one MySQL transaction | Atomicity is limited to the single-database local transaction |
| **Notification** | After Pay commits, publish `TicketIssuedEvent` on a best-effort basis and persist its projection in a consumer-local transaction | Notification delivery is not guaranteed across broker/JVM failure windows |

See [core flows](docs/architecture/core-flows.md) for complete sequences, failure classifications, and state machines.

## 5. Engineering Highlights

### 5.1 Conditional MySQL Stock Update

- **Problem**: concurrent requests can observe the same remaining stock and oversell it.
- **Decision**: use an atomic `UPDATE` guarded by `available_stock > 0`, with affected rows determining success.
- **Guarantee**: only requests that actually decrement the inventory row continue through the flow.
- **Trade-off**: a hot Session can still contend on a MySQL row lock. See [InventoryMapper.xml](eventhub-inventory/src/main/resources/mapper/inventory/InventoryMapper.xml) and [NormalReservationConcurrencyIntegrationTest](eventhub-app/src/test/java/com/eventhub/order/NormalReservationConcurrencyIntegrationTest.java).

### 5.2 Inventory Conservation

- **Problem**: multiple lifecycle paths can make inventory components negative or let their total drift.
- **Decision**: Flyway CHECK constraints require non-negative components and `available + locked + sold = total`.
- **Guarantee**: MySQL rejects writes that violate the inventory invariant.
- **Trade-off**: the constraints protect authoritative MySQL state but cannot remove a temporary Redis/MySQL synchronization window. See the [database schema](docs/database/schema.md) and [indexes and constraints](docs/database/indexes.md).

### 5.3 One Purchase per User and Session

- **Problem**: a user could create duplicate effective orders or bypass the rule through terminal transitions.
- **Decision**: a generated `purchase_guard` column and unique constraint cover `WAIT_PAY`, `PAID`, and `REFUNDED`.
- **Guarantee**: one user and Session retain at most one active or historical purchase record; only `CANCELLED` and `CLOSED` release the guard.
- **Trade-off**: `REFUNDED` does not permit repurchasing the same Session; this is an explicit V1 business rule. See [OrderMapper.xml](eventhub-order/src/main/resources/mapper/order/OrderMapper.xml) and [schema.md](docs/database/schema.md).

### 5.4 Order / Inventory / Ticket Transaction Atomicity

- **Problem**: a paid Order without sold inventory or an issued Ticket would create cross-domain inconsistency.
- **Decision**: Order owns payment orchestration, while Inventory and Ticket join the same MySQL transaction through public APIs.
- **Guarantee**: all three changes commit or roll back together.
- **Trade-off**: this guarantee relies on the modular monolith's single-database transaction boundary. See [PaymentTicketAtomicityIntegrationTest](eventhub-app/src/test/java/com/eventhub/order/PaymentTicketAtomicityIntegrationTest.java).

### 5.5 Redis Lua FLASH Admission

- **Problem**: sending every popular FLASH request directly to MySQL amplifies entry-point contention.
- **Decision**: use four Lua primitives for preparation, admission, settlement, and order-lifecycle synchronization.
- **Guarantee**: inventory and user-state transitions are atomic within each Lua operation.
- **Trade-off**: Lua atomicity cannot cover the subsequent MySQL commit; ambiguous failures conservatively retain pending state, and there is no automatic reconciliation. See [flash_reserve.lua](eventhub-inventory/src/main/resources/lua/flash_reserve.lua) and [FlashReservationFailureIntegrationTest](eventhub-app/src/test/java/com/eventhub/flash/FlashReservationFailureIntegrationTest.java).

### 5.6 Idempotent RabbitMQ Notification Projection

- **Problem**: messages may be delivered more than once, while notification persistence failures must not reverse an already committed payment.
- **Decision**: publish on a post-commit best-effort basis; the consumer uses an independent local transaction and validates idempotency through unique `event_id` and `(type, business_reference)` constraints.
- **Guarantee**: an equivalent duplicate does not create a second projection, while conflicting duplicate facts fail.
- **Trade-off**: the current `AUTO` acknowledgement/no-requeue baseline has no Outbox, Confirm/Return, or Retry/DLQ, so delivery is not guaranteed. See [UserNotificationService](eventhub-notification/src/main/java/com/eventhub/notification/service/UserNotificationService.java) and [UserNotificationConsumerIntegrationTest](eventhub-app/src/test/java/com/eventhub/notification/UserNotificationConsumerIntegrationTest.java).

## 6. Technology Stack

| Layer | Technology |
| --- | --- |
| Runtime | Java 17, Spring Boot 3.5.16, Spring MVC |
| Security | Spring Security, JWT |
| Persistence | MyBatis-Plus 3.5.17, MyBatis XML, Flyway |
| Database | MySQL 8.4.11 / InnoDB |
| High-concurrency admission | Redis 8.2.9, Spring Data Redis, Lettuce, Redis Lua |
| Messaging | RabbitMQ 4.3.5-management, Spring AMQP |
| Testing | JUnit 5, Mockito, MockMvc, real MySQL/Redis/RabbitMQ integration tests |
| Build | Maven Wrapper 3.3.4, Maven 3.9.16 |

## 7. Quick Start

Prerequisites: Git, JDK 17, Docker Engine, and `docker compose`, with host ports `3306`, `6379`, `5672`, `15672`, and `8080` available.

> Before running the build and startup commands below, follow the complete [local development guide](docs/deployment/local-development.md): copy `.env.example` to `.env`, safely import its variables into the same shell that will run Java, and generate a random 32-byte `EVENTHUB_JWT_SECRET_BASE64`. The host Java process does not load `.env` automatically.

Start the infrastructure and wait for MySQL, Redis, and RabbitMQ to become healthy:

```sh
docker compose up -d
docker compose ps
```

PowerShell:

```powershell
.\mvnw.cmd -pl eventhub-app -am package -DskipTests
java -jar .\eventhub-app\target\eventhub-app.jar --server.address=127.0.0.1
```

POSIX:

```sh
sh ./mvnw -pl eventhub-app -am package -DskipTests
java -jar eventhub-app/target/eventhub-app.jar --server.address=127.0.0.1
```

Minimal smoke path: `Register → Login → GET /api/users/me`. See the [HTTP API guide](docs/api/http-api.md) for requests and response contracts.

The default Compose published ports and the local HTTP startup examples above are restricted to `127.0.0.1`; they are not a production deployment configuration. Gate B passed Windows local fresh-clone, Docker, application startup, and HTTP smoke verification on the frozen baseline before this remediation. The loopback changes have only been statically verified; Quick Start was not rerun. The complete POSIX/Linux/macOS workflow and GitHub remote cloning remain unverified.

## 8. Testing & Verification

Frozen local verification records (not rerun in this batch):

| Scope | Suites | Tests | Failures | Errors | Skipped | Result |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| 2026-10-12: historical Full Maven Reactor | 77 | 365 | 0 | 0 | 0 | `BUILD SUCCESS` |
| 2026-10-16: Gate C, Windows local fresh-clone `clean verify` | 77 | 365 | 0 | 0 | 0 | `BUILD SUCCESS`, Exit Code `0` |

The 2026-10-12 entry is the historical baseline preserved in [development progress](docs/development/progress.md). Gate C is this Public Release's actual regression on the baseline before the loopback remediation, covering Root + 9 Modules; it does not verify a runtime rerun of the changed configuration. Neither record is a GitHub CI status, cross-platform verification, or production deployment verification. Keep raw logs, XML, and temporary credentials local; do not publish them. The project currently has no publishable benchmark measurements and makes no QPS, TPS, P95/P99, or coverage claim.

## 9. Documentation & Roadmap

| Document | Contents |
| --- | --- |
| [Architecture overview](docs/architecture/overview.md) | Module boundaries, dependency direction, transactions, and infrastructure responsibilities |
| [Core flows](docs/architecture/core-flows.md) | NORMAL, FLASH, payment, notification, state machines, and failure boundaries |
| [Local development](docs/deployment/local-development.md) | Environment variables, secret generation, build, startup, and smoke testing |
| [HTTP API](docs/api/http-api.md) | Authentication, endpoints, requests, responses, and error semantics |
| [Database schema](docs/database/schema.md) / [Indexes and constraints](docs/database/indexes.md) | Tables, fields, indexes, and database safety lines |
| [V1 Specification](docs/specification/eventhub-v1.md) | Original requirements, evolution path, and planned boundaries |
| [Development Progress](docs/development/progress.md) | Completed phases, historical verification, and follow-up work |

Potential next steps include Transactional Outbox, Publisher Confirm/Return, Retry/DLQ, automatic reconciliation, measured performance benchmarks, and CI. All are **PLANNED / DEFERRED**, with no promised delivery date.

## 10. Contributing, Security & License

- Read [CONTRIBUTING.md](CONTRIBUTING.md) before contributing.
- See [SECURITY.md](SECURITY.md) for security reporting guidance. GitHub Private Vulnerability Reporting is not enabled, and no private reporting channel is currently available; do not disclose unpatched vulnerabilities in public Issues.
- EventHub is licensed under the [Apache License 2.0](LICENSE). See [NOTICE](NOTICE) for third-party attribution.

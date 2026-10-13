# Contributing to EventHub

Thank you for helping improve EventHub. This guide describes the contributor-facing rules for proposing, implementing, and validating changes. Internal Codex, agent, hook, and Phase/Batch workflows are not contributor requirements.

## 1. Project scope

EventHub is a Java 17 event reservation and limited-ticketing backend. It is a Maven multi-module modular monolith: one Spring Boot application, assembled by `eventhub-app`, runs in one JVM. It is not a microservices project.

Before changing behavior, review the [architecture overview](docs/architecture/overview.md), [core flows](docs/architecture/core-flows.md), and [HTTP API guide](docs/api/http-api.md). Keep changes aligned with the implemented system rather than planned features in design documents.

## 2. Getting started

Follow the [local development guide](docs/deployment/local-development.md) to configure the environment, start MySQL, Redis, and RabbitMQ, and build the application. Do not commit `.env`, tokens, passwords, private logs, or other secrets.

Run the full reactor verification from the repository root when the change warrants it:

PowerShell:

```powershell
.\mvnw.cmd verify
```

POSIX:

```sh
sh ./mvnw verify
```

The POSIX wrapper is tracked with mode `100644`, so do not assume `./mvnw` is executable. No GitHub Actions workflow is currently configured; report the commands you actually ran and any environment limitation.

## 3. Architecture rules

- Preserve the Maven multi-module modular monolith and the single `eventhub-app` Composition Root.
- Keep Maven dependencies directional and free of cycles.
- Call another business module only through its public `api` package.
- Do not access another module's Mapper, Repository, internal Service, Redis adapter, MQ adapter, or persistence implementation.
- Keep Controllers in `eventhub-app`; Controllers handle HTTP and delegate business work.
- Keep domain-specific code out of `eventhub-common`.
- Preserve MySQL as the authoritative business state; Redis and RabbitMQ do not replace it.
- Do not introduce a new business module or replace the established technology stack without prior discussion.

## 4. Discussing changes

Open or join an issue before implementing a large feature, breaking API or schema change, architecture change, or new infrastructure dependency. Describe the problem, expected behavior, compatibility impact, and a focused solution. Do not include sensitive vulnerability details in a public issue; follow [SECURITY.md](SECURITY.md).

Small fixes may proceed directly when their intent and scope are clear. Keep each contribution focused and avoid unrelated refactoring.

## 5. Development workflow

1. Create a short-lived topic branch from the current `main` branch.
2. Make the smallest coherent change that solves the stated problem.
3. Add or update tests for changed behavior and failure paths.
4. Update affected documentation when an API, architecture, schema, Redis, RabbitMQ, or operational fact changes.
5. Review the final diff for scope, secrets, generated files, and unintended configuration changes.

Use concise commit subjects in the repository's established `<type>: <summary>` style, such as `feat:`, `fix:`, `test:`, `docs:`, or `chore:`. Do not rewrite shared history.

## 6. Tests and verification

Choose verification that matches the change:

- Pure domain behavior: module-level JUnit tests.
- HTTP, validation, or security contracts: relevant `eventhub-app` web/integration tests.
- SQL, constraints, or transactions: tests against real MySQL.
- Redis Lua or FLASH behavior: tests against real Redis.
- RabbitMQ topology or delivery behavior: tests against real RabbitMQ.
- Important Java, Spring, Maven, database, or configuration changes: full `verify` when the required services are available.

Documentation-only changes do not require the complete infrastructure regression suite. In every pull request, list the commands actually run. If a relevant check was not run, write `Not run — <reason>` and explain the remaining risk. Never weaken a valid test merely to obtain a green build.

## 7. Pull request expectations

A pull request should:

- explain the problem and the resulting behavior;
- link the related issue when one exists;
- remain small enough to review as one logical change;
- include relevant tests and meaningful assertions;
- describe compatibility, migration, and operational impact;
- update documentation when project facts change; and
- disclose verification gaps without attaching credentials, tokens, private logs, or sensitive data.

The project has no published stable API, LTS branch, or guaranteed compatibility schedule. Discuss potentially breaking changes before implementation rather than assuming they are accepted.

## 8. Database migration rules

Flyway migrations under `eventhub-app/src/main/resources/db/migration/` are the schema authority. Do not rewrite an existing versioned migration that may already have been applied. Implement a reviewed schema change as a new migration, then update Java mappings, SQL, tests, and database documentation together.

Preserve database constraints that protect inventory, order, ticket, idempotency, and lifecycle invariants. A successful compile is not proof that a schema change is safe.

## 9. Documentation consistency

Documentation must describe the current implementation and verified evidence. Do not present planned functionality, runtime checks, reliability guarantees, or performance figures as completed work. Update only the documents affected by the change, and use repository-relative links.

## 10. Security reporting

Do not report sensitive vulnerabilities through a public issue, pull request, or discussion. GitHub Private Vulnerability Reporting is planned but is not yet enabled because the public repository has not been created. Read [SECURITY.md](SECURITY.md) for the current reporting limitation before sharing any details.

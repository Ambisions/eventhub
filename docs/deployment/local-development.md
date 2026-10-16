# EventHub Local Development

This guide describes the source-aligned local development path for EventHub. Run every setup, Docker, and Maven command from the repository root unless a step says otherwise.

> Local development only: the credentials and direct database operations in this guide are not a production deployment or administrator-provisioning procedure.

## 1. Prerequisites

Required:

- Git.
- JDK 17 with both `java` and `javac` available through `PATH` or `JAVA_HOME`.
- Docker Engine with the `docker compose` command.
- PowerShell 7 or later (`pwsh`) for the PowerShell examples. Windows PowerShell 5.1 does not provide the random-number API used below.
- Free host ports `3306`, `6379`, `5672`, `15672`, and `8080`.
- Network access to Maven Central on the first Maven Wrapper run unless Maven 3.9.16 is already cached.

The repository uses Maven Wrapper 3.3.4 to download Maven 3.9.16. A separately installed Maven is not required. The application uses Spring Boot 3.5.16 and Java 17; see the [root POM](../../pom.xml), [application POM](../../eventhub-app/pom.xml), and [wrapper properties](../../.mvn/wrapper/maven-wrapper.properties).

For the POSIX examples, also provide:

- `head`, `base64`, and `tr` for random JWT secret generation.
- `curl` and `sed` for the HTTP smoke test.
- A POSIX-compatible `sh`.

## 2. Environment variables

Start from the tracked [`.env.example`](../../.env.example):

```powershell
Copy-Item .env.example .env
```

```sh
cp .env.example .env
```

The root `.env` is ignored by Git. Do not commit it. The included values are local-development examples, not production credentials.

| Variable | Docker Compose | Host Java process | Requirement |
| --- | --- | --- | --- |
| `MYSQL_DATABASE` | Yes | Yes; default `eventhub` | Required by Compose |
| `MYSQL_USER` | Yes | Yes; default `eventhub` | Required by Compose |
| `MYSQL_PASSWORD` | Yes | Yes | Required |
| `MYSQL_ROOT_PASSWORD` | Yes | No | Required by Compose |
| `REDIS_HOST` | No | Yes; default `127.0.0.1` | Optional locally |
| `REDIS_PORT` | No | Yes; default `6379` | Optional locally |
| `RABBITMQ_HOST` | No | Yes; default `127.0.0.1` | Optional locally |
| `RABBITMQ_PORT` | No | Yes; default `5672` | Optional locally |
| `RABBITMQ_DEFAULT_USER` | Yes | Yes | Required |
| `RABBITMQ_DEFAULT_PASS` | Yes | Yes | Required |
| `EVENTHUB_JWT_SECRET_BASE64` | No | Yes | Required; valid Base64 decoding to at least 32 bytes |

Docker Compose reads the root `.env` automatically. A host Java process does not: the application has no dotenv loader. Import `.env` into the terminal that will build and start EventHub, then replace the JWT placeholder with a fresh session-local secret.

The default profile uses [`application.yml`](../../eventhub-app/src/main/resources/application.yml). Do not reuse the deterministic secret from `application-test.yml`; it exists only for automated tests.

## 3. Safe `.env` import

The import snippets below accept the repository's strict `NAME=value` format. They skip blank lines and full-line comments, split only at the first `=`, validate names, remove only paired surrounding quotes, and preserve literal `$`, `${...}`, `$()`, and additional `=` characters. They deliberately do not evaluate the file as shell code.

Keep variable names and `=` adjacent. Inline comments are treated as part of the value.

### PowerShell 7+

```powershell
Get-Content -LiteralPath .env | ForEach-Object {
    $line = $_
    if ([string]::IsNullOrWhiteSpace($line) -or $line.TrimStart().StartsWith('#')) {
        return
    }

    $separator = $line.IndexOf('=')
    if ($separator -lt 1) {
        throw "Invalid .env assignment: $line"
    }

    $name = $line.Substring(0, $separator).Trim()
    $value = $line.Substring($separator + 1)
    if ($name -notmatch '^[A-Za-z_][A-Za-z0-9_]*$') {
        throw "Invalid .env variable name: $name"
    }

    $pairedDoubleQuotes = $value.Length -ge 2 -and $value.StartsWith('"') -and $value.EndsWith('"')
    $pairedSingleQuotes = $value.Length -ge 2 -and $value.StartsWith("'") -and $value.EndsWith("'")
    if ($pairedDoubleQuotes -or $pairedSingleQuotes) {
        $value = $value.Substring(1, $value.Length - 2)
    }

    [Environment]::SetEnvironmentVariable($name, $value, 'Process')
}
```

This does not use `Invoke-Expression`. The variables exist only in the current PowerShell process and child processes.

### Linux and macOS

```sh
cr=$(printf '\r')
while IFS= read -r line || [ -n "$line" ]; do
  line=${line%"$cr"}
  case "$line" in
    ''|'#'*) continue ;;
  esac

  case "$line" in
    *=*) ;;
    *) printf 'Invalid .env assignment: %s\n' "$line" >&2; exit 1 ;;
  esac

  name=${line%%=*}
  value=${line#*=}
  case "$name" in
    ''|[0-9]*|*[!A-Za-z0-9_]*)
      printf 'Invalid .env variable name: %s\n' "$name" >&2
      exit 1
      ;;
  esac

  case "$value" in
    \"*\") value=${value#\"}; value=${value%\"} ;;
    \'*\') value=${value#\'}; value=${value%\'} ;;
  esac

  export "$name=$value"
done < .env
```

Do not replace this with `eval`, `source .env`, or `. .env`. Those commands execute the file as shell input and can interpret interpolation or command-substitution characters.

## 4. Generate a JWT signing secret

The application signs access tokens with HS256. `EVENTHUB_JWT_SECRET_BASE64` must be valid Base64 whose decoded value is at least 32 bytes.

After importing `.env`, replace its public placeholder in the current session.

PowerShell:

```powershell
$ErrorActionPreference = 'Stop'
$bytes = [byte[]]::new(32)
[System.Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
$env:EVENTHUB_JWT_SECRET_BASE64 = [Convert]::ToBase64String($bytes)
```

The fail-fast setting prevents the session from continuing with an uninitialized byte array if random generation is unavailable or fails.

POSIX:

```sh
for required_command in head base64 tr; do
  command -v "$required_command" >/dev/null 2>&1 || {
    printf 'Required command not found: %s\n' "$required_command" >&2
    exit 1
  }
done
[ -r /dev/urandom ] || { printf '/dev/urandom is not readable\n' >&2; exit 1; }

export EVENTHUB_JWT_SECRET_BASE64="$(
  head -c 32 /dev/urandom | base64 | tr -d '\r\n'
)"
```

Do not print, commit, or reuse this value as a long-lived shared secret. It is session-local: reopening the terminal requires importing the environment and generating or supplying a secret again.

## 5. Start local infrastructure

The tracked [`docker-compose.yml`](../../docker-compose.yml) starts:

| Service | Image | Host port | Health check |
| --- | --- | --- | --- |
| MySQL | `mysql:8.4.11` | `127.0.0.1:3306` | `mysqladmin ping` |
| Redis | `redis:8.2.9` | `127.0.0.1:6379` | `redis-cli ping` |
| RabbitMQ | `rabbitmq:4.3.5-management` | `127.0.0.1:5672` | `rabbitmq-diagnostics -q ping` |
| RabbitMQ Management UI | same container | `127.0.0.1:15672` | RabbitMQ health check |

```sh
docker compose up -d
docker compose ps
```

Wait until MySQL, Redis, and RabbitMQ all report `healthy`. The default published ports bind only to IPv4 loopback; do not remove that boundary when using the public local-development example credentials. The RabbitMQ Management UI is optional for local diagnostics. This Compose topology is for local development, not production deployment.

The application does not expose `/actuator/health`; do not use that path as a readiness check.

## 6. Build the application

PowerShell:

```powershell
.\mvnw.cmd -pl eventhub-app -am package -DskipTests
```

POSIX:

```sh
sh ./mvnw -pl eventhub-app -am package -DskipTests
```

The POSIX command intentionally invokes `sh`: `mvnw` is currently tracked with Git mode `100644`, so a fresh clone must not assume it is directly executable.

`-am` builds the application and its reactor dependencies. `-DskipTests` skips test execution for this quick-start build; it is not a substitute for the project verification suite.

## 7. Start EventHub

PowerShell:

```powershell
java -jar .\eventhub-app\target\eventhub-app.jar --server.address=127.0.0.1
```

POSIX:

```sh
java -jar eventhub-app/target/eventhub-app.jar --server.address=127.0.0.1
```

The actual JAR name is `eventhub-app.jar`, as fixed by the application POM. Keep the application in the same terminal session that contains the imported environment variables. The process uses Spring Boot's default HTTP port `8080`; the repository does not explicitly set `server.port`. The command-line `--server.address=127.0.0.1` restricts these host-local startup examples to IPv4 loopback without changing `application.yml` or imposing a global/container deployment bind address.

Gate B verified Windows local fresh-clone packaging, Docker health, application startup, and the HTTP smoke path on the frozen baseline before this remediation. The new loopback bindings and startup flag have only been statically verified; this batch did not rerun Docker startup, application smoke, or Maven. POSIX/Linux/macOS and GitHub remote-clone workflows remain unverified.

## 8. Minimal HTTP smoke

The minimal smoke path needs no ADMIN account:

```text
Register → Login → GET /api/users/me
```

Use a second terminal while the application is running. The synthetic credentials below are local examples only.

### PowerShell

```powershell
$baseUri = 'http://127.0.0.1:8080'
$credentials = @{
    username = 'quickstart.user'
    password = 'LocalOnly-Password-42'
    displayName = 'Quick Start User'
}

$registerResponse = Invoke-WebRequest `
    -Method Post `
    -Uri "$baseUri/api/auth/register" `
    -ContentType 'application/json' `
    -Body ($credentials | ConvertTo-Json -Compress)
if ($registerResponse.StatusCode -ne 201) { throw 'Expected register HTTP 201' }
$registered = $registerResponse.Content | ConvertFrom-Json

$loginBody = @{
    username = $credentials.username
    password = $credentials.password
} | ConvertTo-Json -Compress
$loginResponse = Invoke-WebRequest `
    -Method Post `
    -Uri "$baseUri/api/auth/login" `
    -ContentType 'application/json' `
    -Body $loginBody
if ($loginResponse.StatusCode -ne 200) { throw 'Expected login HTTP 200' }
$login = $loginResponse.Content | ConvertFrom-Json

$meResponse = Invoke-WebRequest `
    -Method Get `
    -Uri "$baseUri/api/users/me" `
    -Headers @{ Authorization = "$($login.tokenType) $($login.accessToken)" }
if ($meResponse.StatusCode -ne 200) { throw 'Expected /me HTTP 200' }
$me = $meResponse.Content | ConvertFrom-Json

if ($me.id -ne $registered.id -or $me.username -ne $registered.username) {
    throw 'Registered user and /me identity do not match'
}
$me | Select-Object id, username, displayName, role, createdAt, updatedAt
```

Expected safe response facts:

- Register returns HTTP `201` with `id`, `username`, `displayName`, `role`, `createdAt`, and `updatedAt`; it does not return a password or password hash.
- Login returns HTTP `200` with `accessToken`, `tokenType`, and `expiresAt`; `tokenType` is `Bearer`.
- `/me` returns HTTP `200` for the same generated user ID and canonical username.

The access token remains only in the second terminal process.

### POSIX `curl`

```sh
base_uri='http://127.0.0.1:8080'

register_result=$(curl -sS -w '__HTTP_STATUS__:%{http_code}' \
  -H 'Content-Type: application/json' \
  -d '{"username":"quickstart.user","password":"LocalOnly-Password-42","displayName":"Quick Start User"}' \
  "$base_uri/api/auth/register")
register_status=${register_result##*__HTTP_STATUS__:}
register_json=${register_result%__HTTP_STATUS__:*}
[ "$register_status" = '201' ] || { printf '%s\n' "$register_json" >&2; exit 1; }

login_result=$(curl -sS -w '__HTTP_STATUS__:%{http_code}' \
  -H 'Content-Type: application/json' \
  -d '{"username":"quickstart.user","password":"LocalOnly-Password-42"}' \
  "$base_uri/api/auth/login")
login_status=${login_result##*__HTTP_STATUS__:}
login_json=${login_result%__HTTP_STATUS__:*}
[ "$login_status" = '200' ] || { printf '%s\n' "$login_json" >&2; exit 1; }

token=$(printf '%s\n' "$login_json" | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p')
[ -n "$token" ] || { printf 'Login response did not contain accessToken\n' >&2; exit 1; }

me_result=$(curl -sS -w '__HTTP_STATUS__:%{http_code}' \
  -H "Authorization: Bearer $token" \
  "$base_uri/api/users/me")
me_status=${me_result##*__HTTP_STATUS__:}
me_json=${me_result%__HTTP_STATUS__:*}
[ "$me_status" = '200' ] || { printf '%s\n' "$me_json" >&2; exit 1; }
printf '%s\n' "$me_json"

unset token login_json login_result me_json me_result register_json register_result
```

Confirm that the register and `/me` responses contain the same generated `id` and canonical `username`. The script does not invent an ID, hard-code a token, or write a token to a tracked file.

See the [HTTP API guide](../api/http-api.md) for the complete endpoint contract.

## 9. Optional local ADMIN bootstrap

> LOCAL DEVELOPMENT ONLY. EventHub has no ADMIN sign-up API, and direct role updates are not a production administration procedure.

Global roles are `USER` and `ADMIN`. An organizer is any authenticated user who owns an Event; `ORGANIZER` is not a global role.

First register a dedicated local user such as `quickstart.admin`. Usernames are trimmed and lowercased, so use the canonical value in SQL. Open an interactive MySQL client; `--password` prompts without placing the password on the command line:

```sh
docker compose exec mysql sh -lc 'exec mysql --user=root --password "$MYSQL_DATABASE"'
```

Identify the exact user before changing anything:

```sql
SELECT id, username, display_name, role
FROM users
WHERE username = 'quickstart.admin';
```

Proceed only when the result identifies exactly the intended local user. The following conditional statement is the update safety line; the preceding human-readable query is not an atomic authorization workflow:

```sql
UPDATE users
SET role = 'ADMIN'
WHERE username = 'quickstart.admin'
  AND role = 'USER';

SELECT ROW_COUNT() AS affected_rows;

SELECT id, username, role
FROM users
WHERE username = 'quickstart.admin';
```

Require `affected_rows = 1`. A result of `0` means no eligible USER row was changed; re-check the exact identity and current role instead of applying an unconditional update.

JWT role claims are fixed when the token is issued. Log in again to obtain a new token carrying `ADMIN`; the old USER token does not change. To restore the local user later:

```sql
UPDATE users
SET role = 'USER'
WHERE username = 'quickstart.admin'
  AND role = 'ADMIN';

SELECT ROW_COUNT() AS affected_rows;
```

Log in again after restoring the role.

Restoring the database role does not revoke an already issued ADMIN token; without a token denylist, that old token keeps its embedded ADMIN claim until it expires. Discard it and use the newly issued USER token.

## 10. Troubleshooting

### The app reports a missing or invalid JWT secret

Import `.env` in the same terminal, then generate a fresh 32-byte secret as shown above. The placeholder in `.env.example` is intentionally not usable.

### The app cannot connect to MySQL, Redis, or RabbitMQ

Run `docker compose ps` and wait for all three services to become healthy. Confirm that the default ports are free and that the host process received the matching environment variables.

### `./mvnw` is not executable on POSIX

Use `sh ./mvnw ...`. The tracked file mode is currently `100644`.

### The JAR path does not exist

Build from the repository root and use `eventhub-app/target/eventhub-app.jar`, not a version-suffixed name.

### `/actuator/health` returns no useful result

No Actuator health endpoint is configured. Use Compose health status for infrastructure and the minimal HTTP smoke for the application.

## 11. Cleanup

Stop the application with `Ctrl+C`, then stop local infrastructure:

```sh
docker compose down
```

The named volumes remain. To delete all local EventHub database, Redis, and RabbitMQ data, explicitly run the destructive local-only cleanup:

```sh
docker compose down -v
```

## 12. Verification status

| Evidence | Status |
| --- | --- |
| Commands and paths checked against repository source | `SOURCE-DESIGNED` |
| PowerShell and POSIX snippets statically checked | `STATICALLY VERIFIED` |
| Gate B: Windows local fresh clone, packaging, and application startup | `PASS / VERIFIED` on the pre-remediation baseline |
| Gate B: Docker health and Register → Login → `/api/users/me` smoke | `PASS / VERIFIED` on the pre-remediation baseline |
| Gate C: Windows local fresh-clone full `clean verify`, 2026-10-16 | `BUILD SUCCESS`; Root + 9 Modules; 77 Suites / 365 Tests; Failures / Errors / Skipped = 0 / 0 / 0; Exit Code 0 |
| Current loopback Compose bindings and local startup examples | `STATICALLY VERIFIED`; runtime not rerun after remediation |
| Complete POSIX/Linux/macOS workflow and cross-platform behavior | `NOT EXECUTED` |
| GitHub remote clone | `NOT EXECUTED` |
| Public/production deployment | `NOT VERIFIED / NOT CLAIMED` |

Gate B/C evidence remains frozen against baseline commit `1625d2b8786966f81ebb038c595459aec3110c92`, before the current loopback remediation. This batch checks configuration and documentation statically only; it does not extend those runtime results to the modified configuration. The temporary isolated Gate C environment is not a production deployment recipe. Retain original logs, XML, local runtime paths, and credentials privately; only a reviewed, redacted verification summary is suitable for publication. Unverified platforms and GitHub remote cloning must not be described as passed.

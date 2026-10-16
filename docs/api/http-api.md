# EventHub HTTP API

本指南记录当前源码中 10 个 Controller 暴露的 28 个 HTTP endpoint。它是源码导航和调用参考，不是 OpenAPI 规范；项目当前没有 Swagger/OpenAPI 页面。

## 1. API conventions

- 本地开发 base URL：`http://127.0.0.1:8080`；启动参数和监听边界见[本地开发指南](../deployment/local-development.md#7-start-eventhub)。
- 除注册、登录和公开活动查询外，请求使用 `Authorization: Bearer <access-token>`。
- 请求和响应采用 JSON；没有请求体的操作不应发送占位 JSON。
- 创建用户、活动、场次和订单返回 `201 Created`；登出返回 `204 No Content`；其余成功响应默认返回 `200 OK`。
- 路径中的 `eventId`、`sessionId` 和 `orderId` 是数字 ID，`ticketNo` 是字符串。
- 示例 ID 只用于展示格式，不能假定它们存在于本地数据库。

## 2. JWT authentication

`POST /api/auth/login` 返回以下结构：

```json
{
  "accessToken": "<JWT returned by login>",
  "tokenType": "Bearer",
  "expiresAt": "2026-10-12T09:30:00Z"
}
```

JWT 使用 HS256 签名，issuer 为 `eventhub`，默认有效期为 30 分钟。已签发 token 包含 `sub`、`username`、`role`、`iat` 和 `exp` claims。服务端从经过签名和校验的 token 中构造当前用户 principal；客户端不能用请求体、查询参数或自报的用户 ID 替代它。`POST /api/auth/logout` 是无状态的 `204` 操作，当前实现没有 token denylist，因此已签发 token 在过期前仍可使用。

本地配置和安全生成签名密钥的方法见[本地开发指南](../deployment/local-development.md)。

## 3. USER、ADMIN 与 ownership

系统只有两个全局 role：`USER` 和 `ADMIN`。Organizer 不是第三种全局 role；创建活动的登录用户通过 event ownership 成为该活动及其场次的 organizer。

- `/api/admin/**` 和 FLASH prepare 要求 `ADMIN`。
- `/api/organizer/**` 要求登录，并由领域层检查 event/session ownership。
- order 查询及生命周期操作要求登录用户是 order owner。
- ticket 查询要求登录用户是 ticket owner。
- check-in 要求登录用户是 ticket 所属场次的 organizer，而不是 ticket holder。
- `ADMIN` 不会自动绕过 order 或 ticket ownership。

缺少或无效 JWT 通常返回 `401`；已认证但不满足 Security role matcher 返回 `403`。为隐藏资源存在性，领域 ownership 检查通常表现为 `404`，不能把所有无权限情况统一理解为 `403`。

## 4. Complete endpoint matrix

`Access` 中的 `Owner` 表示由领域层执行资源 ownership 检查。

| # | Method | Path | Access | Request | Success response |
|---:|---|---|---|---|---|
| 1 | POST | `/api/auth/register` | Public | `RegisterRequest` | `201 UserResponse` |
| 2 | POST | `/api/auth/login` | Public | `LoginRequest` | `200 AccessTokenResponse` |
| 3 | POST | `/api/auth/logout` | Authenticated | None | `204` empty body |
| 4 | GET | `/api/users/me` | Authenticated | None | `200 UserResponse` |
| 5 | PUT | `/api/users/me` | Authenticated | `UpdateCurrentUserRequest` | `200 UserResponse` |
| 6 | GET | `/api/events` | Public | Query: `page`, `size` | `200 PublishedEventPageResponse` |
| 7 | GET | `/api/events/{eventId}` | Public, published only | None | `200 PublicEventResponse` |
| 8 | GET | `/api/events/{eventId}/sessions` | Public, published only | None | `200 EventSessionResponse[]` |
| 9 | GET | `/api/sessions/{sessionId}` | Public, published only | None | `200 EventSessionResponse` |
| 10 | GET | `/api/organizer/events/{eventId}` | Authenticated + event Owner | None | `200 ManagedEventResponse` |
| 11 | POST | `/api/organizer/events` | Authenticated; caller becomes Owner | `CreateEventRequest` | `201 ManagedEventResponse` |
| 12 | PUT | `/api/organizer/events/{eventId}` | Authenticated + event Owner | `UpdateEventRequest` | `200 ManagedEventResponse` |
| 13 | POST | `/api/organizer/events/{eventId}/sessions` | Authenticated + event Owner | `CreateEventSessionRequest` | `201 EventSessionResponse` |
| 14 | PUT | `/api/organizer/sessions/{sessionId}` | Authenticated + session Owner | `UpdateEventSessionRequest` | `200 EventSessionResponse` |
| 15 | POST | `/api/organizer/events/{eventId}/submit` | Authenticated + event Owner | None | `200 ManagedEventResponse` |
| 16 | PUT | `/api/organizer/sessions/{sessionId}/inventory` | Authenticated + session Owner | `ConfigureInventoryRequest` | `200 InventoryResponse` |
| 17 | GET | `/api/admin/events/{eventId}` | ADMIN | None | `200 ReviewEventResponse` |
| 18 | POST | `/api/admin/events/{eventId}/approve` | ADMIN | None | `200 ReviewEventResponse` |
| 19 | POST | `/api/admin/events/{eventId}/reject` | ADMIN | `RejectEventRequest` | `200 ReviewEventResponse` |
| 20 | POST | `/api/events/{eventId}/sessions/{sessionId}/flash/prepare` | ADMIN | None | `200 FlashPreparationResponse` |
| 21 | POST | `/api/sessions/{sessionId}/orders` | Authenticated; caller owns order | None | `201 OrderResponse` |
| 22 | POST | `/api/sessions/{sessionId}/flash/reserve` | Authenticated; caller owns order | None | `201 OrderResponse` |
| 23 | GET | `/api/orders/{orderId}` | Authenticated + order Owner | None | `200 OrderResponse` |
| 24 | POST | `/api/orders/{orderId}/pay` | Authenticated + order Owner | None | `200 PaymentResult` |
| 25 | POST | `/api/orders/{orderId}/cancel` | Authenticated + order Owner | None | `200 OrderResponse` |
| 26 | POST | `/api/orders/{orderId}/refund` | Authenticated + order Owner | None | `200 RefundResponse` |
| 27 | GET | `/api/tickets/{ticketNo}` | Authenticated + ticket Owner | None | `200 TicketResponse` |
| 28 | POST | `/api/tickets/{ticketNo}/check-in` | Authenticated + session organizer Owner | None | `200 TicketCheckInResponse` |

列表查询的 `page` 默认值为 `0`、最小值为 `0`；`size` 默认值为 `20`，范围为 `1..100`。

## 5. Auth and user examples

注册请求：

```json
{
  "username": "quickstart.user",
  "password": "LocalOnly-Password-42",
  "displayName": "Quick Start User"
}
```

登录请求使用相同的 username 和 password：

```json
{
  "username": "quickstart.user",
  "password": "LocalOnly-Password-42"
}
```

更新当前用户显示名：

```json
{
  "displayName": "Updated Local User"
}
```

`RegisterRequest` 和 `LoginRequest` 的 `username`、`password` 不能为 `null`。领域规则会 trim 并 lowercase username，接受 `[a-z0-9._-]{3,64}`；password 长度为 8–64 个 Unicode code point，并且 UTF-8 编码不得超过 72 bytes。`displayName` 可省略；非空时 trim 后长度为 1–100。更新显示名也由同一领域规则校验。

`UserResponse` 字段为：`id`、`username`、`displayName`、`role`、`createdAt`、`updatedAt`。完整可执行的 Register → Login → `/api/users/me` smoke 见[本地开发指南](../deployment/local-development.md#8-minimal-http-smoke)。

## 6. Event and session

创建或更新活动：

```json
{
  "title": "EventHub Local Demo",
  "description": "A local-only event"
}
```

`title` 必填，trim 后长度为 1–200；`description` 可省略。新活动状态为 `DRAFT`。`ManagedEventResponse` 包含 `id`、`title`、`description`、`status`、`rejectionReason`、`createdAt`、`updatedAt`；管理员使用的 `ReviewEventResponse` 还包含 `organizerUserId`。公开的 `PublicEventResponse` 只返回已发布活动，并包含 `id`、`title`、`description`、`status`、`createdAt`、`updatedAt`。

管理员拒绝活动时发送：

```json
{
  "reason": "Please clarify the event description"
}
```

`reason` 必填，trim 后长度为 1–500。

创建 NORMAL 场次示例：

```json
{
  "name": "Morning Session",
  "venue": "Room A",
  "saleMode": "NORMAL",
  "saleStartAt": "2026-10-12T09:30:00",
  "saleEndAt": "2026-10-12T10:30:00",
  "startAt": "2026-10-12T11:00:00",
  "endAt": "2026-10-12T12:00:00"
}
```

`name` 必填，trim 后长度为 1–200；`venue` 可省略或使用 blank（归一化为 `null`），非空时 trim 后最多 255 个字符。创建时省略 `saleMode` 会使用 `NORMAL`；更新时 `saleMode: null` 保留现有模式。四个时间字段必填，且必须满足 `saleStartAt < saleEndAt` 和 `startAt < endAt`。`EventSessionResponse` 返回 `id`、`eventId`、`name`、`venue`、`saleMode`、四个业务时间以及 `createdAt`、`updatedAt`。

`PublishedEventPageResponse` 只有 `items`、`page`、`size`、`hasNext`，没有 `total` 字段。

## 7. Inventory

活动仍可编辑时，owner 可以为场次配置库存：

```json
{
  "totalStock": 100
}
```

`totalStock` 必填且不能小于 `0`。`InventoryResponse` 返回 `sessionId`、`totalStock`、`availableStock`、`lockedStock`、`soldStock`、`updatedAt`。库存开始被使用后，重新配置可能因状态冲突返回 `409`。

## 8. NORMAL and FLASH reservation

- NORMAL：调用 `POST /api/sessions/{sessionId}/orders`。领域层要求场次为 NORMAL，并处于销售窗口。
- FLASH：活动发布且库存已干净配置后，由 `ADMIN` 在开售前调用 FLASH prepare；返回 `{"sessionId":1,"state":"PREPARED"}`。开售后用户调用 `POST /api/sessions/{sessionId}/flash/reserve`。

两种预约均从 JWT principal 获取 user ID，不接收请求体，并返回 `201 OrderResponse`。数据库 purchase guard 会阻止同一用户在同一场次再创建订单，只要已有订单处于 `WAIT_PAY`、`PAID` 或历史 `REFUNDED` 状态；因此退款后仍不能重新购买该场次。NORMAL 路径的 MySQL guard 冲突映射为 `ACTIVE_PURCHASE_EXISTS`；FLASH 路径可能先由 Redis admission 返回 `FLASH_ALREADY_ADMITTED`。两者都是 HTTP `409`。`CANCELLED` 或 `CLOSED` 历史不占用数据库 guard。当前 FLASH 流程使用 Redis 做原子 admission，再同步落 MySQL；这里没有把尚未实现的 RabbitMQ 异步下单描述成当前 API 行为。

`OrderResponse` 字段为 `id`、`orderNo`、`sessionId`、`status`、`expireAt`、`createdAt`、`updatedAt`。

## 9. Order pay, cancel and refund

订单 owner 可以查询、支付、取消或退款自己的订单。状态机和销售规则仍由领域层强制执行：

- `pay` 返回 `PaymentResult`，结构为 `{"order": <OrderResponse shape>, "ticket": <TicketResponse shape>}`。当前实现模拟支付成功后的业务状态转换和出票，没有接入真实支付网关。
- `cancel` 返回更新后的 `OrderResponse`。
- `refund` 返回 `RefundResponse`，字段为 `orderId`、`status`。

`ADMIN` 身份本身不会绕过 order owner 检查。过期、非法状态、退款窗口关闭或已核销票券等冲突返回 `409`。

## 10. Ticket and check-in

`GET /api/tickets/{ticketNo}` 只允许 ticket owner 查询，`TicketResponse` 字段为 `ticketNo`、`orderId`、`sessionId`、`status`、`createdAt`。

`POST /api/tickets/{ticketNo}/check-in` 不由票券持有人授权；它要求调用者拥有该票券对应的 event/session。成功返回 `TicketCheckInResponse`，字段为 `ticketNo`、`sessionId`、`status`。非 owner 的 organizer 请求通过 `404` 隐藏票券存在性；未到核销窗口、已核销或已退款等状态返回 `409`。

## 11. Error semantics

由 `GlobalExceptionHandler` 或 Security authentication/access-denied handlers 显式处理的错误使用以下结构：

```json
{
  "code": "ERROR_CODE",
  "message": "Human-readable message"
}
```

| Status | Meaning in the current implementation |
|---:|---|
| `400` | Bean Validation、请求体缺失/格式错误，或领域输入无效 |
| `401` | 缺失/无效/过期 JWT，或登录凭据错误 |
| `403` | 已认证，但不满足 Security role matcher，例如非 ADMIN 调用管理接口 |
| `404` | 资源不存在，或领域 owner check 有意隐藏资源存在性 |
| `409` | 重复数据、非法生命周期、销售窗口、库存、订单或票券状态冲突 |
| `503` | FLASH 依赖 Redis 的服务暂时不可用 |

Validation 通常使用 `VALIDATION_ERROR`，缺失或畸形请求体使用 `INVALID_REQUEST`。未由这些 handlers 显式映射的框架级错误只应依赖已验证的 HTTP status，不能假定同一 JSON shape。具体领域错误码以响应为准；本指南不虚构每个 Controller 的穷举错误码。未映射或不支持的路径/方法还会受到 Security deny-by-default 约束，因此也不应笼统承诺所有此类请求都返回某个 REST 状态。

## 12. Time semantics

Event、session、inventory、order、ticket 和 user DTO 中的业务/审计时间是 `LocalDateTime`，JSON 形式不含 offset，例如：

```json
{
  "saleStartAt": "2026-10-12T09:30:00"
}
```

Login response 的 `expiresAt` 是 `Instant`，使用带 `Z` 的形式，例如：

```json
{
  "expiresAt": "2026-10-12T09:30:00Z"
}
```

应用 Clock 与 JDBC 配置使用 UTC，但 `LocalDateTime` 自身不携带时区或 offset；因此不能把所有 HTTP `LocalDateTime` 宣称为协议层保证的 UTC instant。

## 13. Full business workflow

Minimal smoke 只需要普通用户完成 Register → Login → `/api/users/me`，不要求管理员。完整业务演示的顺序是：

1. 用户注册并登录；该用户创建 `DRAFT` event，因此成为 owner。
2. Owner 创建 NORMAL 或 FLASH session，并在 event 可编辑时配置 inventory。
3. Owner submit event。
4. 本地 `ADMIN` 审核并 approve event，使其发布。
5. FLASH 场次还要求 `ADMIN` 在 sale start 之前完成 prepare；NORMAL 场次不需要这一步。
6. 进入 sale window 后，用户选择对应的 NORMAL reserve 或 FLASH reserve。
7. Order owner 支付订单，系统签发 ticket；随后可查询 ticket，并由场次 organizer 执行 check-in。

本地 ADMIN 获取方式及其安全边界见[可选本地 Admin bootstrap](../deployment/local-development.md#9-optional-local-admin-bootstrap)。

## 14. Current limitations and source map

当前限制：

- 没有 OpenAPI/Swagger UI。
- 没有真实 payment gateway；支付 endpoint 只执行当前领域支付流程。
- 没有 notification 查询或列表 API。
- 没有 ADMIN sign-up API。
- Logout 不会主动撤销已签发 JWT。
- 没有 Actuator health endpoint。
- Gate B 已在原冻结基线上验证 Windows 本地 fresh clone、Docker、应用启动及 Register → Login → `/api/users/me` HTTP Smoke；POSIX/Linux/macOS 完整工作流和 GitHub remote clone 尚未验证。

本指南以以下源码为依据：

- [SecurityConfiguration](../../eventhub-app/src/main/java/com/eventhub/config/SecurityConfiguration.java)
- [Controllers](../../eventhub-app/src/main/java/com/eventhub/controller/)
- [GlobalExceptionHandler](../../eventhub-app/src/main/java/com/eventhub/handler/GlobalExceptionHandler.java)
- [application.yml](../../eventhub-app/src/main/resources/application.yml)
- [Auth API](../../eventhub-auth/src/main/java/com/eventhub/auth/api/)
- [User API](../../eventhub-user/src/main/java/com/eventhub/user/api/)
- [Event API](../../eventhub-event/src/main/java/com/eventhub/event/api/)
- [Inventory API](../../eventhub-inventory/src/main/java/com/eventhub/inventory/api/)
- [Order API](../../eventhub-order/src/main/java/com/eventhub/order/api/)
- [Ticket API](../../eventhub-ticket/src/main/java/com/eventhub/ticket/api/)
- [架构概览](../architecture/overview.md)
- [核心流程](../architecture/core-flows.md)

文档结论分层：endpoint、DTO、Security、错误映射与业务约束是 `SOURCE-GROUNDED`；Gate B 提供原冻结基线上的 Windows 本地 Fresh Quick Start/HTTP Smoke 证据；Gate C 于 2026-10-16 完成该基线的 Windows 本地 fresh-clone 全量 `clean verify`，Root + 9 Modules、77 Suites / 365 Tests、Failures / Errors / Skipped = 0 / 0 / 0、Exit Code 0。当前 loopback Compose 与本地启动参数修正仅作 `STATICALLY VERIFIED`，没有重新运行 Maven、Docker 或 HTTP Smoke；不得将旧基线结果当作修改后配置的 runtime 验证。原始日志、XML 和临时凭据仅保留本地，不公开上传；未验证的平台、GitHub remote clone 和生产部署不作通过声明。

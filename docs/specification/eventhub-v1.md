# EventHub 高并发活动预约与票务平台

## 项目设计与开发说明书 V1.0

---

## 1. 项目概述

### 1.1 项目名称

**EventHub —— 高并发活动预约与票务平台**

### 1.2 项目定位

EventHub 是一个面向技术大会、线下沙龙、展览、演出等场景的活动预约与限量票务平台。

系统同时支持：

- 普通活动报名
- 热门活动高并发抢票
- 活动多场次管理
- 订单支付与超时取消
- 电子票生成与核销
- 退款与库存恢复
- 候补排队
- 消息通知
- 高并发库存控制
- Redis / MySQL 最终一致性
- RabbitMQ 异步削峰
- 幂等、限流、补偿和对账

本项目重点不是实现大量 CRUD，而是围绕：

**高并发、库存、订单、消息可靠性、数据一致性以及性能优化**

构建完整 Java 后端业务闭环。

---

# 2. 项目目标

项目主要用于展示 Java 后端工程能力，覆盖：

### Java 基础

- 集合
- ConcurrentHashMap
- BlockingQueue
- ThreadLocal
- ThreadPoolExecutor
- CAS 思想
- 并发安全

### Spring

- Spring Boot
- Spring MVC
- Spring Security
- Spring Transaction
- Validation
- AOP
- Actuator

### 数据库

- MySQL
- InnoDB
- 事务
- 行锁
- MVCC
- 条件 UPDATE
- 唯一约束
- 联合索引
- EXPLAIN
- 慢 SQL

### Redis

- Cache Aside
- String
- Set
- Hash
- ZSet
- Lua
- 缓存穿透
- 缓存击穿
- 缓存雪崩
- 限流
- 幂等

### RabbitMQ

- 异步
- 削峰
- Publisher Confirm
- Return Callback
- Manual ACK
- Retry
- DLQ
- 延迟消息
- 消费幂等

### JVM / 性能

- JVM Heap
- G1
- GC Log
- Thread Dump
- HikariCP
- JMeter
- Micrometer
- Actuator
- Arthas / VisualVM

---

# 3. 核心业务规则

## 3.1 活动模型

一个 Event 可以拥有多个 EventSession。

```text
Event
  │
  │ 1:N
  ▼
EventSession
  │
  │ 1:1
  ▼
EventInventory

```

真正参与抢票和库存计算的是：

```text
sessionId

```

而不是 eventId。

---

## 3.2 用户购票限制

同一个用户在同一个 Session：

**最终只能成功购买一张票。**

用户产生：

```text
WAIT_PAY
→ CANCELLED

```

或者：

```text
WAIT_PAY
→ CLOSED

```

之后允许重新抢票。

但是一旦：

> 当前 Phase 7 已实现 `PAID` 时同步签发 Ticket；Pay 的 Local MySQL Transaction
> 由 `eventhub-order` 拥有，Ticket 签发作为 `MANDATORY` 事务参与者。

```text
PAID
→ Ticket generated

```

即使以后退款，也不允许再次参与同场次抢票。

---

## 3.3 订单生命周期

当前 `CURRENT / FROZEN / IMPLEMENTED` 持久状态与转换为：

```text
WAIT_PAY → PAID
WAIT_PAY → CANCELLED
WAIT_PAY → CLOSED
```

Timeout 是 operation / trigger，`CLOSED` 才是持久状态。

未来退款转换 `PAID → REFUNDED` 为
`FUTURE / FROZEN / NOT_IMPLEMENTED`。下图同时展示当前状态与这一 Future
Contract，不表示 `REFUNDED` 已进入当前 Migration 或 Production behavior。

```text
                     PAY
WAIT_PAY ─────────────────────→ PAID
   │                              │
   │                              │ REFUND
   ├── CANCEL ─→ CANCELLED        ▼
   │                           REFUNDED
   │
   └── TIMEOUT ─→ CLOSED

```

其中当前：

```text
CANCELLED
CLOSED

```

为终态。

未来 `REFUNDED` 为退款终态。

---

## 3.4 库存模型

库存包含：

```text
totalStock
availableStock
lockedStock
soldStock

```

满足：

```text
totalStock
=
availableStock
+
lockedStock
+
soldStock

```

抢票成功：

```text
available -1
locked +1

```

支付：

```text
locked -1
sold +1

```

取消或超时：

```text
locked -1
available +1

```

未来退款（`FUTURE / FROZEN / NOT_IMPLEMENTED`）：

```text
sold -1
available +1

```

---

## 3.5 待支付规则

抢票成功：

```text
创建 WAIT_PAY Order
↓
条件锁定库存
↓
15分钟支付窗口

```

超过 15 分钟：

```text
WAIT_PAY
↓
CLOSED
↓
释放库存

```

---

## 3.6 候补规则

WaitingList capability 为 `FUTURE / PLANNED / NOT_IMPLEMENTED`。本节仅保留
方向性示例；Schema、唯一约束、Redis ZSet、排序、资格时长与 MQ flow 尚未冻结。

售罄后用户可以进入 WaitingList。

未来可能在库存释放时：

```text
WaitingList 候选用户
↓
获得购买资格
↓
限时购买资格（精确时长尚未冻结）

```

系统不自动为候补用户创建订单。

资格过期：

```text
EXPIRED
↓
下一位候补用户获得资格

```

---

# 4. 技术栈

## 后端

```text
Java 17
Spring Boot 3.5.x
Spring MVC
Spring Security
JWT
Jakarta Validation
Spring Transaction

```

## 数据访问

```text
MyBatis-Plus
+
MyBatis XML

```

普通 CRUD：

```text
MyBatis-Plus

```

核心 SQL：

```text
手写 Mapper/XML

```

---

## 数据库

```text
MySQL 8.4 LTS
InnoDB
HikariCP

```

---

## Redis

```text
Redis
Spring Data Redis
Lettuce
Lua

```

V1 不把 Redisson 分布式锁作为核心方案。

---

## 消息队列

```text
RabbitMQ
Spring AMQP

```

---

## 测试与监控

```text
JUnit 5
Mockito
MockMvc
JMeter
Spring Boot Actuator
Micrometer
VisualVM
Arthas

```

---

## 本地基础设施

```text
Docker Compose

├── MySQL
├── Redis
└── RabbitMQ

```

---

# 5. 系统架构

EventHub 当前采用：

**Maven 多模块 + 模块化单体**

不是微服务。

最终只有一个：

```text
eventhub-app.jar

```

运行在一个 JVM 中。

---

# 6. Maven 项目结构

```text
eventhub
│
├── pom.xml
│
├── docker-compose.yml
│
├── README.md
│
├── docs
│   ├── architecture.md
│   ├── database.md
│   ├── redis.md
│   ├── rabbitmq.md
│   └── benchmark.md
│
├── eventhub-common
│
├── eventhub-user
│
├── eventhub-auth
│
├── eventhub-event
│
├── eventhub-inventory
│
├── eventhub-order
│
├── eventhub-ticket
│
├── eventhub-notification
│
└── eventhub-app

```

---

# 7. 模块职责

## eventhub-common

存放真正公共的基础能力：

```text
common
├── exception
├── result
├── constants
├── enums
├── utils
└── context

```

禁止将具体业务 Service 放入 common。

---

## eventhub-user

```text
User
用户资料
用户查询

```

Phase 5 不实现用户状态、封禁、删除、Soft Delete 或 Admin User Management。

---

## eventhub-auth

```text
注册
登录
Access Token JWT
Spring Security
USER / ADMIN Authority
Authenticated Principal

```

Phase 5 使用 Stateless Client Logout，不实现 Refresh Token、Redis Session、
Token Rotation、Token Blacklist 或 Device Session。

---

## eventhub-event

```text
Event
EventSession
活动发布
活动查询
活动审核
场次管理

```

---

## eventhub-inventory

```text
Inventory
库存初始化
库存锁定
库存释放

Future Frozen：FLASH Redis库存、Lua atomic reservation
Future Planned：WaitingList、限流

```

这是系统核心模块之一。

---

## eventhub-order

```text
订单创建
支付
取消
超时
订单状态机

Future Frozen：退款业务规则、FLASH Reservation concept、Outbox/RabbitMQ direction
Not Yet Frozen：Refund owner/API、Reservation persistence model、exact MQ contract

```

这是系统另外一个核心模块。

---

## eventhub-ticket

```text
Current Frozen / Implemented：PAID 同步签发 Ticket、Owner-only 单票查询、Event Owner 核销
Future：Ticket 列表尚未实现
Not Yet Frozen：refund Ticket state/失效方式

```

---

## eventhub-notification

```text
Future Planned：Notification capability
Not Yet Frozen：Exchange、Queue、Routing Key、persistence、retry/DLQ topology

```

V1 可以使用日志模拟真实短信/邮件。

---

## eventhub-app

系统入口和业务编排层：

```text
eventhub-app
├── application
├── controller
├── config
├── handler
├── filter
└── EventHubApplication

```

Controller 统一放在 app。

跨领域流程默认由：

```text
ApplicationService

```

编排。

已批准并冻结的窄例外以对应 Contract 为准。当前 Phase 6 NORMAL Order
Lifecycle 的 Reserve、Pay、Cancel、Timeout 由 `eventhub-order` 拥有本地事务
编排，按 `Order → Inventory` 调用 Public API；App 仍负责 HTTP、Security、
Scheduler、配置与 Composition Root。

例如：

```text
ReservationApi / OrderService.reserve
        │
        ├── EventService
        ├── InventoryService
        └── OrderService

```

---

# 8. 模块依赖原则

必须遵守：

1. 模块依赖保持单向。
2. 禁止循环 Maven 依赖。
3. 禁止跨模块调用其他模块 Mapper。
4. 跨模块只能通过公开 Service/API。
5. Repository / Mapper 只属于自己的领域。
6. Application Service 默认负责跨领域编排；明确冻结的领域生命周期例外除外。
7. Domain Service 负责本领域业务规则。

---

# 9. 核心数据库表

Phase 3 第一版数据库 Baseline 冻结为六张表：

```text
users
events
event_sessions
event_inventory
ticket_order
ticket

```

以下表不进入第一版 Baseline，只有对应功能实际实现时才通过后续
Flyway Migration 增加：

```text
WaitingList persistence
Reservation persistence
Transactional Outbox persistence
Consumer idempotency persistence（only if required）
Notification delivery persistence（only if required）
```

以上为 capability direction，不冻结表名、字段、状态机或约束。

---

# 10. 关键数据库约束

## event\_inventory

```text
UNIQUE(session_id)

```

保证：

```text
Session : Inventory = 1:1

```

---

## ticket\_order

```text
UNIQUE(order_no)

UNIQUE(user_id, session_id, purchase_guard)

```

其中 generated `purchase_guard` 对 `WAIT_PAY` / `PAID` 生成 `1`，
对 `CLOSED` / `CANCELLED` 生成 `NULL`。它阻止同一用户、同一场次出现
重复有效订单，同时允许关闭或取消后重新下单。

索引：

```text
(user_id, created_at)

(session_id)

(status, expire_at)

```

`(session_id, status)` 暂缓，待真实查询路径出现后再评估。
Reservation concept 属于 Future Frozen direction；精确持久化模型、
`reservation_id` 与最终唯一安全线尚未冻结，必须在 FLASH 实现阶段决定。

---

## ticket

```text
UNIQUE(ticket_no)

UNIQUE(order_id)

UNIQUE(user_id, session_id)

```

最终保证：

> 一个用户一个场次最多成功获得一张票。

---

## waiting\_list

WaitingList capability 为 `FUTURE / PLANNED / NOT_IMPLEMENTED`；表结构、唯一
约束与索引尚未冻结。

---

# 11. NORMAL 普通抢票流程

```text
POST /api/sessions/{sessionId}/orders
      ↓
读取 Published Session
      ↓
检查 [saleStartAt, saleEndAt)
      ↓
INSERT WAIT_PAY Order
      ↓
MySQL条件UPDATE库存
      ↓
COMMIT

```

NORMAL 不执行 Active Order 查询前置检查。同一用户、同一 Session 的有效购买由
`uk_ticket_order_effective_purchase` 在 INSERT 时作为最终并发安全线；只有 Order
INSERT 成功后才调用库存条件 UPDATE，二者加入同一个本地 MySQL 事务。

库存 SQL：

```text
UPDATE event_inventory

available_stock = available_stock - 1
locked_stock = locked_stock + 1

WHERE
session_id = ?
AND available_stock > 0

```

通过：

```text
affectedRows

```

判断是否抢票成功。

这里不需要：

```text
synchronized
Redis Lock
SELECT FOR UPDATE

```

---

# 12. FLASH 高并发抢票流程

以下核心链路为 `FUTURE / FROZEN / NOT_IMPLEMENTED`：

```text
User
 ↓
JWT
 ↓
用户限流
 ↓
Session限流
 ↓
Redis Lua
 ↓
Reservation
 ↓
Outbox
 ↓
RabbitMQ
 ↓
Order Consumer
 ↓
MySQL

```

Reservation business concept 已冻结；精确持久化模型、字段、`reservation_id`
与幂等 Key 尚未冻结。以下返回值与查询路径仅为
`FUTURE / PLANNED` 方向，不构成完整 HTTP Contract Freeze。

接口方向可能首先返回：

```text
PROCESSING
+
reservationId

```

然后客户端：

```text
GET /reservations/{reservationId}

```

查询最终结果。

---

# 13. Redis 数据设计

本节为未来设计候选。FLASH Redis + Lua atomic reservation 与 Fail Fast 是
Future Frozen direction；具体 Key、Reservation model 以及 WaitingList
implementation 尚未冻结。

## 普通缓存

```text
eventhub:event:{id}:detail

eventhub:session:{id}:detail

```

---

## 抢票库存

```text
eventhub:session:{id}:stock

```

---

## 当前占用用户

```text
eventhub:session:{id}:occupied-users

```

---

## 历史购买用户

```text
eventhub:session:{id}:purchased-users

```

---

## 用户当前 Reservation

```text
eventhub:session:{id}:reservation-map

```

Hash：

```text
userId → reservationId

```

---

## 候补

```text
eventhub:waiting:{sessionId}:queue

```

ZSet。

---

# 14. 抢票 Lua 职责

一次原子执行：

```text
检查是否已经购买
↓
检查是否已有Reservation
↓
检查售票时间
↓
检查库存
↓
stock - 1
↓
加入 occupied-users
↓
保存 reservationId
↓
创建临时Reservation状态

```

不能使用：

```text
GET
↓
Java判断
↓
DECR

```

这种非原子组合。

---

# 15. RabbitMQ 架构

FLASH 使用 Transactional Outbox → RabbitMQ → Idempotent Consumer 的方向为
`FUTURE / FROZEN / NOT_IMPLEMENTED`。以下队列名只是设计示例，不构成拓扑
Freeze；Notification topology 与 Timeout 的 TTL/DLX 等机制均尚未冻结。

队列示例：

```text
order.create.queue
order.create.dlq

order.delay.queue
order.timeout.queue

notification.queue

```

订单创建：

```text
Redis
↓
Outbox
↓
RabbitMQ
↓
Order Consumer
↓
MySQL

```

---

# 16. MQ 可靠性

Producer reliability 方向（精确机制与 Outbox Schema 尚未冻结）：

```text
Transactional Outbox
Publisher Confirm
Return Callback
Durable Exchange
Durable Queue
Persistent Message

```

Consumer reliability 方向（精确拓扑、Retry/DLQ 与业务 Key 尚未冻结）：

```text
Manual ACK
Retry
DLQ
stable business-key database guard

```

采用：

> At-Least-Once + Idempotent Consumer

而不是追求理论上的 Exactly Once。

---

# 17. 最终一致性

核心原则：

```text
MySQL
= Source of Truth

Redis
= 高并发执行层

RabbitMQ
= 异步传输层

```

跨组件通过：

```text
Local Transaction
+
Outbox
+
Retry
+
Idempotency
+
Compensation
+
Reconciliation

```

保证最终一致。

---

# 18. 缓存策略

活动详情：

```text
Cache Aside

```

查询：

```text
Redis
↓ MISS
MySQL
↓
Redis

```

更新：

```text
UPDATE MySQL
↓
COMMIT
↓
DELETE Cache

```

---

## 缓存穿透

```text
参数校验
+
Null Cache

```

---

## 缓存击穿

```text
热点预热
+
随机 TTL
+
SET NX 重建互斥

```

---

## 缓存雪崩

```text
TTL 打散
+
预热
+
不同数据不同 TTL
+
限流/降级

```

---

# 19. Redis 故障策略

NORMAL：

```text
Redis故障
↓
有限降级到MySQL

```

FLASH：

```text
Redis故障
↓
Fail Fast

```

禁止热门抢票接口突然让全部流量进入 MySQL。

---

# 20. 限流

FLASH 请求：

```text
JWT
 ↓
User Rate Limit
 ↓
Session Rate Limit
 ↓
Redis Lua

```

用户限流：

```text
userId + sessionId

```

Session 限流：

```text
sessionId

```

V1 使用：

```text
Redis Lua Fixed Window

```

具体阈值必须通过压测决定。

---

# 21. 幂等设计

不同业务使用不同幂等方案。

### 抢票

本节为未来 FLASH 幂等方向；Reservation concept 已冻结，但精确 Key 与
`reservation_id` 尚未冻结。

```text
userId + sessionId
→ stable reservation business key

```

重复请求返回已有业务结果。

### 支付

```text
WHERE status = WAIT_PAY

```

### 取消

```text
WAIT_PAY → CANCELLED

```

### 退款

`FUTURE / FROZEN / NOT_IMPLEMENTED`：重复退款不得重复执行有效业务副作用。

```text
PAID → REFUNDED

```

### MQ

```text
stable business-key database guard

```

Reservation 精确持久化模型、`reservation_id` 与精确幂等 Key 尚未冻结。

### 库存释放

```text
Reservation状态
+
Lua

```

不使用“万能分布式锁”解决所有问题。

---

# 22. 线程池设计

RabbitMQ Consumer：

```text
使用 Listener Container 自身并发

```

不额外套线程池。

自定义线程池：

```text
reconciliationExecutor
warmupExecutor
notificationExecutor（可选）

```

要求：

```text
显式 corePoolSize
显式 maxPoolSize
有界 queue
显式 rejection policy
ThreadNamePrefix
异常处理
MDC 清理
优雅关闭

```

禁止核心代码直接：

```text
Executors.newFixedThreadPool()

```

---

# 23. User / Auth API 与安全 Contract

## Authority 与 Ownership

Phase 5 只使用两个全局 Authority：

```text
USER
ADMIN
```

Organizer 不是全局 Role。任何认证 `USER` 都可以创建 Event，创建后通过：

```text
events.organizer_user_id == authenticated user id
```

表达资源 Ownership。注册接口始终创建 `USER`，客户端不得提交或选择
`ADMIN`。V1 不提供 Role 修改、Admin 授权或 RBAC 管理 API。Admin Provisioning
属于 Operational / Bootstrap Concern。

## Username 与 Password

`username` 是唯一登录标识。注册与登录都必须先执行：

```text
trim → lowercase
```

最终只持久化 canonical username。长度为 3–64，允许字符为：

```text
a-z 0-9 . _ -
```

Password 长度为 8–64 characters，并作为 opaque secret 处理：不 trim、不
lowercase、不执行 Unicode normalization。UTF-8 编码后超过 72 bytes 的输入必须
拒绝，不能依赖 bcrypt 静默截断。生产实现使用 Spring Security
`DelegatingPasswordEncoder`，默认编码器为 bcrypt。数据库只保存
`password_hash`；任何 HTTP Response 均不得返回 Password 或 Password Hash。

## JWT 与 Logout

Phase 5 只签发 30 分钟 Access Token：

```text
Authorization: Bearer <token>
algorithm: HS256
issuer: eventhub
claims: sub=userId, username, role, iat, exp, iss
```

HS256 Signing Secret 必须来自 Environment / Secret，不得硬编码、不得提供真实
默认值，并且至少为 256-bit。本阶段不增加 `jti`、`deviceId` 或 `sessionId`。

`POST /api/auth/logout` 的冻结语义是 Stateless Client Logout：服务端不维护
Token Session、不写 Redis Blacklist；客户端删除 Access Token。已签发 Token 在
`exp` 前仍然有效，因此不得宣称 Logout 会让旧 Token 立即失效。

## Auth

```http
POST /api/auth/register

POST /api/auth/login

POST /api/auth/logout

```

- Register Request 不允许包含 `role`。
- Register 永远创建 `role = USER`。
- Login identifier 只允许 canonical `username`，不支持 email 或 phone。
- Logout 只要求有效 Access Token，不产生服务端 Token 状态。

---

## User

```http
GET /api/users/me

PUT /api/users/me

```

User API 只覆盖当前用户资料，不提供用户列表、用户状态、删除或 Admin User
Management。

---

# 24. Event / EventSession Contract

## Ownership

创建 Event 时，`organizer_user_id` 必须来自 Authenticated Principal，Create Event
Request 不允许提交该字段。Event Module 接收调用方传入的 `currentUserId` 并执行：

```text
event.organizer_user_id == currentUserId
```

Ownership Check。Event Module 不因为 `organizer_user_id` Logical FK 依赖
`eventhub-user` Mapper 或 API。

## Event State Machine 与编辑规则

```text
DRAFT
  └── submit → PENDING_REVIEW
                   ├── approve → PUBLISHED
                   └── reject  → REJECTED

REJECTED
  └── submit → PENDING_REVIEW
```

禁止增加 `REJECTED → DRAFT` 状态迁移。

- `DRAFT`：Event 可编辑；Session 可新增/修改；可 submit。
- `REJECTED`：Event 可编辑；Session 可新增/修改；可重新 submit。
- `PENDING_REVIEW`：Event 不可修改；Session 不可新增/修改。
- `PUBLISHED`：V1 Event 不可修改；V1 Session 不可新增/修改。
- EventSession 没有独立状态机，其修改资格由所属 Event Status 决定。

Reject Request 必须包含非空 `reason`，最大 500 characters。状态更新语义为：

```text
reject:   status = REJECTED,       rejection_reason = reason
submit:   status = PENDING_REVIEW, rejection_reason = NULL
approve:  status = PUBLISHED,      rejection_reason = NULL
```

Event Owner 查询自己被拒绝的 Event 时可以读取 `rejectionReason`；Anonymous
Public Response 不暴露审核原因。

## Public Visibility

下列 Public API 只允许访问所属 Event 状态为 `PUBLISHED` 的资源。该限制必须在
Backend 执行，不能依赖前端过滤或隐藏 ID。`GET /api/events/{eventId}` 永远保持
Public Detail 语义；即使请求携带 Owner 或 Admin JWT，也不能通过该 Public URL
读取非 Published Event 或自动切换为 Managed View。

公开接口：

```http
GET /api/events

GET /api/events/{eventId}

GET /api/events/{eventId}/sessions

GET /api/sessions/{sessionId}

```

Organizer：

```http
GET /api/organizer/events/{eventId}

POST /api/organizer/events

PUT /api/organizer/events/{eventId}

POST /api/organizer/events/{eventId}/sessions

PUT /api/organizer/sessions/{sessionId}

POST /api/organizer/events/{eventId}/submit

```

- Organizer 路由要求认证。
- Organizer Detail 只允许 Event Owner，支持读取所有 Event 状态，并在 `REJECTED`
  时向 Owner 返回 `rejectionReason`。
- Event 不存在或不属于当前用户时统一返回 Not Found，避免资源枚举。
- Create Event Request 不得包含 `organizer_user_id`。
- Update、Session Management 与 Submit 只允许 Event Owner。

Admin：

```http
GET /api/admin/events/{eventId}

POST /api/admin/events/{eventId}/approve

POST /api/admin/events/{eventId}/reject

```

- `/api/admin/**` 只允许 `ADMIN`。
- Admin Detail 是只读审核视图，不授予 Organizer Update 或 Session Mutation 权限。
- Reject Request 必须为 `{ "reason": "..." }`，最大 500 characters。
- Admin 的 Review Authority 不自动授予 Organizer Update/Session CRUD 权限。

## Phase 5 Security Matrix

| API / Capability | Anonymous | Authenticated `USER` | Event Owner | `ADMIN` |
| --- | --- | --- | --- | --- |
| Register / Login | Allow | Allow | Allow | Allow |
| Published Event / Session query | Allow | Allow | Allow | Allow |
| Logout / `/api/users/me` | Deny | Allow | Allow | Allow |
| Create Event | Deny | Allow | Allow | Allow as an authenticated user |
| Update Event / manage Session / submit | Deny | Deny when not owner | Allow for owned Event | Only when also the Event Owner |
| Approve / Reject | Deny | Deny | Deny unless separately `ADMIN` | Allow |
| `/api/admin/**` | Deny | Deny | Deny unless separately `ADMIN` | Allow |

Event Owner 是每个 Event 上动态判断的资源权限，不是 JWT Role。Admin Review
Authority 也不构成对全部 Organizer Resource 的隐式 CRUD 权限。

---

# 25. Reservation API

NORMAL 当前接口：

```http
POST /api/sessions/{sessionId}/orders

```

该接口需要 Bearer Token、无 Request Body，成功返回 `201 Created` 与当前
`WAIT_PAY` Order；用户身份只来自 JWT Principal。

FLASH 未来接口方向（`FUTURE / PLANNED`，完整 HTTP Contract 尚未冻结）：

```http
POST /api/sessions/{sessionId}/reservations

```

FLASH 可能返回：

```text
reservationId
PROCESSING

```

查询：

```http
GET /api/reservations/{reservationId}

```

返回：

```text
PROCESSING

SUCCESS
+ orderNo

FAILED

```

---

# 26. Order API

```http
GET /api/orders/{orderId}

POST /api/orders/{orderId}/pay

POST /api/orders/{orderId}/cancel

```

以上接口均要求 Bearer Token，并且只能查询或操作当前用户自己的 Order。不存在的
Order 与其他用户的 Order 都统一返回 `ORDER_NOT_FOUND`，避免通过 ID 枚举资源。

支付仅允许未过期的 `WAIT_PAY` Order 原子转换为 `PAID`。`eventhub-order` 拥有
Pay 的 Local MySQL Transaction，写入顺序为 Order `WAIT_PAY -> PAID`、Inventory
`locked_stock -> sold_stock`、Ticket `INSERT VALID`；Ticket 签发以 `MANDATORY`
加入同一事务，失败时整个 Pay 事务回滚。当 `paymentNow >= expire_at` 时拒绝支付。
成功返回 `PaymentResult(OrderView, TicketView)`，调用方可从 `TicketView` 获得
`ticketNo`。

主动取消仅允许 `WAIT_PAY` Order 转换为 `CANCELLED`，随后在同一 Local
Transaction 中将一份库存从 `locked_stock` 释放回 `available_stock`。已过期但仍为
`WAIT_PAY` 的 Order 允许主动取消。Order Conditional UPDATE 的 affected-row 结果是
库存副作用的唯一执行门槛，因此重复请求以及 Pay / Cancel 竞争都最多产生一次库存变更。

Timeout Scanner 以数据库为事实来源，按固定延迟扫描一批满足
`status = WAIT_PAY AND expire_at <= scanNow` 的 Order，并按
`expire_at ASC, id ASC` 稳定排序。每个候选 Order 使用独立 Local Transaction 执行
`WAIT_PAY -> CLOSED`，随后将一份库存从 `locked_stock` 释放回
`available_stock`；只有 Order Conditional UPDATE 成功的执行者才能触发库存释放。
单个 Order 失败不会回滚或阻断同批其他候选，重复扫描和并发 Pay / Cancel / Timeout
竞争最多产生一次终态迁移与一次库存副作用。

Order 列表、退款与支付提供方集成仍属于后续 Batch；Ticket 同步签发及单票查询
已在 Phase 7 实现。

---

# 27. Ticket API

当前已实现的 Ticket API：

```http
GET /api/tickets/{ticketNo}
POST /api/tickets/{ticketNo}/check-in

```

该接口要求认证；`ticketNo` 由 `TKT-` 加大写 UUID32 组成。Validated JWT 经
`EventHubAuthenticatedUser.userId()` 提供查询身份，数据库查询同时限定
`ticket_no` 与 `user_id`。缺失、非 Owner、非法 `ticketNo` 均返回
`TICKET_NOT_FOUND` / HTTP 404；ADMIN 非 Owner 不获得绕过权限。
响应仅包含 `ticketNo`、`orderId`、`sessionId`、`status`、`createdAt`。

Ticket 签发以数据库 `UNIQUE(ticket_no)`、`UNIQUE(order_id)` 和
`UNIQUE(user_id, session_id)` 为最终安全线。重复 Order 或 User/Session 出票
返回 `TICKET_ALREADY_ISSUED`，并回滚整个 Pay 事务；不返回已有 Ticket。

`POST /api/tickets/{ticketNo}/check-in` 要求认证，且只有 Ticket Session 所属
Event 的 Organizer Owner 可以核销；ADMIN 不获得 Ownership bypass，但 ADMIN
同时为该 Event Owner 时可以正常核销。缺失 Ticket、非法 `ticketNo` 或非 Owner
统一返回 `TICKET_NOT_FOUND` / HTTP 404，避免暴露资源存在性。

核销窗口严格为 `sessionStartAt <= checkInTime < sessionEndAt`：开始前返回
`TICKET_CHECK_IN_NOT_STARTED`，结束时刻及之后返回 `TICKET_CHECK_IN_CLOSED`。
App 层 `CheckInApplicationService` 捕获一次 UTC 微秒时间并持有
`READ_COMMITTED` 本地事务，依次解析 Ticket target、执行 Event Owner/时间授权，
再调用 `MANDATORY` Ticket mutation。Ticket 通过 Session-bound Conditional UPDATE
执行唯一状态转换 `VALID -> USED`；合法窗口内重复核销返回
`TICKET_ALREADY_USED`。

错误优先级为 Resource/Ownership hiding、Time Window、Conditional UPDATE、USED
classification。因此 non-owner 即使 Ticket 已 USED 或窗口已关闭仍返回 404；Owner
的 USED Ticket 在关闭窗口返回 `TICKET_CHECK_IN_CLOSED`，在合法窗口返回
`TICKET_ALREADY_USED`。成功响应为 HTTP 200，且仅包含 `ticketNo`、`sessionId`、
`status`。Ticket 列表 `GET /api/tickets` 仍属于 Future；退款 Ticket state、失效方式
以及 Refund 与 Check-in 的交互仍未冻结。

---

# 28. WaitingList API

WaitingList capability 为 `FUTURE / PLANNED`；以下路径只是候选，完整 HTTP
Contract 尚未冻结。

```http
POST /api/sessions/{sessionId}/waiting-list

DELETE /api/sessions/{sessionId}/waiting-list

GET /api/sessions/{sessionId}/waiting-list/me

```

---

# 29. 核心 Application Services

对真实由 App 拥有的复杂跨领域 Use Case，可以在：

```text
eventhub-app/application

```

建立命名明确的 ApplicationService。当前 Phase 6 NORMAL Order Lifecycle 是窄
例外，由 `eventhub-order` 拥有 Reserve / Pay / Cancel / Timeout 事务编排。
Refund 与 WaitingList 的事务所有者尚未冻结，下列名称不得作为硬性要求：

```text
ReservationApplicationService

PaymentApplicationService

CancellationApplicationService

RefundApplicationService

WaitingListApplicationService

EventPublishApplicationService

```

不要构造一个：

```text
EventHubService

```

负责整个系统。

---

# 30. 第一阶段代码目录建议

以 Order 为例：

```text
eventhub-order
└── src/main/java/com/eventhub/order
    │
    ├── domain
    │   ├── TicketOrder.java
    │   ├── OrderStatus.java
    │   └── ReservationRequest.java
    │
    ├── service
    │   ├── OrderService.java
    │   └── ReservationService.java
    │
    ├── repository
    │   ├── OrderRepository.java
    │   └── ReservationRepository.java
    │
    ├── mapper
    │   ├── OrderMapper.java
    │   └── ReservationMapper.java
    │
    ├── message
    │   ├── CreateOrderMessage.java
    │   └── OrderTimeoutMessage.java
    │
    └── consumer
        ├── CreateOrderConsumer.java
        └── OrderTimeoutConsumer.java

```

不要一开始追求完整 DDD。

我们的目标是：

```text
边界清楚
+
代码容易读
+
面试容易讲

```

---

# 31. 开发阶段划分

项目实现不应该一次性实现最终版。

严格按照：

```text
Baseline
↓
Correctness
↓
Redis
↓
MQ
↓
Reliability
↓
Performance

```

推进。

---

# 32. 七天开发计划

## Day 1 —— 工程骨架 + 基础业务

目标：

**让项目跑起来。**

未来能力方向：

```text
Maven Multi Module
Spring Boot
MySQL
MyBatis-Plus
Docker Compose

users
events
event_sessions
event_inventory
ticket_order
ticket

```

实现：

```text
注册
登录

创建活动
查询活动
创建Session
查询Session

```

当日验收：

```text
Spring Boot成功启动
MySQL连接成功
基础CRUD成功
JWT登录成功

```

---

# Day 2 —— NORMAL 普通抢票

当前实现由 `eventhub-order` 的 Order Service 拥有事务编排，不使用 App
`ReservationApplicationService`。

链路：

```text
校验Session
↓
INSERT WAIT_PAY Order
↓
MySQL条件UPDATE库存

```

Phase 6 Batch 2 已完成 NORMAL Reservation 与 `WAIT_PAY` Order；Batch 3 已完成
Owner-only Order Query、同步 Pay 与主动 Cancel；Batch 4 已完成数据库驱动的有界
Timeout Scanner。Phase 7 已在 Pay 中同步签发 Ticket。以下能力保留到后续 Batch：

```text
退款
支付提供方集成

```

V1 Timeout 已采用：

```text
@Scheduled fixedDelay
bounded database scan
one independent Local Transaction per Order
WAIT_PAY -> CLOSED + locked -> available

```

RabbitMQ TTL / DLX、延迟消息以及 FLASH Reservation Timeout 不属于当前 NORMAL
Timeout Baseline，留待后续阶段按真实需求演进。

当日必须验证：

```text
100并发
抢10张票

最终成功 <= 10
stock >= 0
没有重复Ticket

```

这一天下班时，EventHub 应该已经是一个完整可用项目。

---

# Day 3 —— Redis + Lua

完成：

```text
活动详情缓存
Session缓存

stock
occupied-users
purchased-users
reservation-map

库存预热
抢票 Lua
释放 Lua

用户级基础限流

```

FLASH：

```text
请求
↓
Redis Lua
↓
同步创建订单

```

先不接 RabbitMQ。

然后进行：

```text
MySQL版本
VS
Redis版本

```

第一次性能对比。

---

# Day 4 —— RabbitMQ

完成：

```text
RabbitMQ配置

CreateOrderMessage

OrderCreateExchange
OrderCreateQueue

Consumer

Manual ACK

Publisher Confirm

Return Callback

Retry
DLQ

```

FLASH 升级：

以下为未来路线图，不冻结 `reservationId`、HTTP path 或持久化模型：

```text
Redis
↓
RabbitMQ
↓
MySQL

```

完成：

```text
stable reservation business key

```

以及：

```http
GET /reservations/{id}

```

---

# Day 5 —— 最终一致性

以下是未来路线图。Outbox / RabbitMQ 方向已冻结，但精确 Schema、消息类型、
拓扑、Retry/DLQ、补偿模型与幂等 Key 尚未冻结。

完成：

```text
Reservation persistence（exact table / fields not yet frozen）
Transactional Outbox（exact schema / states not yet frozen）
Outbox Publisher
Reconciliation / Redis Compensation
Idempotent Consumer

```

Message types 在实现阶段冻结；当前不预设 `ORDER_PAID`、`ORDER_TIMEOUT` 或
`ORDER_REFUNDED` 等精确名称。

这一天下班时重点测试：

```text
重复MQ消息

Consumer异常

Redis扣成功但DB失败

重复支付

重复取消

重复退款

```

---

# Day 6 —— 缓存 / 限流 / 线程池 / 监控

完成：

```text
Cache Aside

Null Cache

随机 TTL

热点重建锁

User Rate Limit

Session Rate Limit

JWT Immediate Revocation / Blacklist（Phase 5 不实现，未来按需评估）

reconciliationExecutor

warmupExecutor

Actuator
Micrometer

```

并完善：

```text
GlobalExceptionHandler
ErrorCode
RequestId
MDC

```

---

# Day 7 —— 压测 + 优化 + 简历材料

最后一天不再疯狂加业务。

重点：

```text
JMeter
Actuator
GC Log
MySQL EXPLAIN
RabbitMQ Management
Redis Metrics

```

完成至少三轮测试：

```text
V1 MySQL

V2 Redis

V3 Redis + RabbitMQ

```

记录：

```text
QPS
P95
P99
错误率
MySQL压力
RabbitMQ积压
JVM指标

```

最后产出：

```text
README.md

architecture.md

benchmark.md

项目架构图

核心抢票时序图

数据库ER图

简历描述

面试项目介绍

```

---

# 33. 功能优先级

## P0 —— 必须完成

```text
登录
Event
Session
Inventory
NORMAL抢票
FLASH抢票
订单
模拟支付
Ticket
Redis Lua
RabbitMQ
Consumer幂等
Outbox
补偿
限流
基础压测

```

---

## P1 —— 尽量完成

```text
退款
候补
缓存击穿
JWT Immediate Revocation / Blacklist（Phase 5 不实现，未来按需评估）
DLQ
延迟关单
线程池隔离
Actuator

```

---

## P2 —— 时间允许再做

```text
真实邮件
WebSocket
Prometheus
Grafana
二维码
复杂Admin后台
复杂RBAC
Bloom Filter
XXL-Job

```

如果七天不够：

> **砍 P2，绝对不要牺牲 P0 的完成度。**

---

# 34. 第一阶段验收标准

最终系统必须证明以下事实。

### 数据正确性

```text
库存永远不能 < 0

total =
available + locked + sold

一个用户一个Session最多一张Ticket

重复MQ不能创建重复订单

重复释放不能导致库存增加两次

```

### 高并发

例如：

```text
10000 users
100 tickets

```

最终：

```text
成功资格 <= 100
有效订单 <= 100
Ticket <= 100

```

---

# 35. 可靠性验收

至少人工制造：

### Case 1

```text
RabbitMQ重复投递

```

结果：

```text
只有一个订单

```

### Case 2

```text
支付接口连续请求10次

```

结果：

```text
只执行一次支付状态转换

```

### Case 3

```text
取消接口连续请求10次

```

结果：

```text
库存只恢复一次

```

### Case 4

```text
Consumer抛异常

```

结果：

```text
Retry
↓
DLQ / 后续补偿

```

### Case 5

```text
Reservation长时间PROCESSING

```

结果：

```text
Reconciliation最终处理

```

---

# 36. 项目核心技术难点

最终 EventHub 至少形成四个真正可讲的项目难点。

## 难点一：高并发库存

```text
MySQL条件UPDATE
↓
Redis库存前置
↓
Lua原子扣减

```

解决：

```text
超卖
热点行竞争
数据库压力

```

---

## 难点二：削峰

```text
Redis Lua
↓
RabbitMQ
↓
异步订单

```

解决：

```text
瞬时写入峰值

```

---

## 难点三：最终一致性

```text
Reservation
+
Transactional Outbox
+
RabbitMQ
+
Consumer Idempotency
+
Reconciliation
+
Compensation

```

解决：

```text
Redis
RabbitMQ
MySQL

```

跨系统数据一致性。

---

## 难点四：系统稳定性

```text
限流
幂等
缓存治理
线程池隔离
Fail Fast
监控
压测

```

避免高并发时系统整体雪崩。

---

# 37. 架构演进路线

EventHub 最终最重要的故事不是：

> “我用了很多技术。”

而是：

```text
V1
MySQL条件UPDATE
解决超卖
↓
问题：
热点库存行竞争

```

```text
V2
Redis前置库存
↓
问题：
多命令原子性

```

```text
V3
Redis Lua
↓
问题：
成功请求仍瞬时写MySQL

```

```text
V4
RabbitMQ削峰
↓
问题：
跨组件一致性

```

```text
V5
Outbox
幂等
重试
DLQ
补偿
对账
↓
最终一致性

```

然后：

```text
V6
限流
缓存治理
线程池隔离
JVM/数据库优化

```

这条演进路线是整个项目的核心。

---

# 38. 当前明确不做

V1 不引入：

```text
Spring Cloud
Nacos
Gateway
Feign
Seata
Kafka
RocketMQ
Elasticsearch
MongoDB
ShardingSphere
XXL-Job
复杂分布式锁
复杂DDD
CQRS
Event Sourcing

```

原因：

> 当前业务规模不需要，引入只会增加不必要的复杂度。

---

# 39. 项目完成定义 Definition of Done

EventHub V1 只有满足以下条件才算真正完成：

```text
项目可以通过 Docker Compose 启动依赖

Spring Boot可正常运行

数据库schema完整

NORMAL抢票可运行

FLASH抢票可运行

不存在超卖

不存在重复有效票

RabbitMQ可靠消费

补偿机制可运行

基础限流生效

核心接口有测试

完成至少一次真实JMeter压测

README解释架构演进

README明确所有性能数据来自真实测试

```

项目最终应该达到：

> **你不仅能运行它，还能解释为什么每一个关键技术存在。**

这才是 EventHub 对初级 Java 后端面试最重要的价值。

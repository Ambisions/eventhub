---
name: redis-concurrency
description: >
  用于 EventHub 中涉及 Redis、缓存、Lua、FLASH 抢票库存、
  Reservation 临时状态、WaitingList、限流、Redis 故障处理、
  补偿与高并发原子操作的任务。
  当功能需要 Redis Key/TTL 设计、Cache Aside、Lua 原子逻辑、
  FLASH 并发状态控制、限流或 Redis/MySQL 协同时使用。
---

# EventHub Redis & Concurrency

本 Skill 定义 EventHub Redis 相关任务的标准执行流程。

EventHub Phase 10 Batch 3 的 Redis Lua Admission primitives 与 ADMIN Preparation
已经 `COMPLETE / VERIFIED / FROZEN`。Batch 4 的 Redis admission → synchronous
MySQL Reservation 编排与 Batch 5 的 Order lifecycle Redis synchronization 仍为
`NOT STARTED / NOT_IMPLEMENTED`。RabbitMQ async order、Retry / DLQ 与 automatic
reconciliation 仍为 Future；未来异步 Reservation 的精确持久化模型、字段与
`reservation_id` 尚未冻结。WaitingList capability 为 `FUTURE / PLANNED`，具体
Schema、ZSet、资格时长和约束尚未冻结。Fixed Window Rate Limit 同样属于 future
traffic hardening，不是 Phase 10 correctness requirement 或 completion criterion。

主要处理：

- Redis Cache
- Key / TTL
- FLASH Stock
- Lua
- occupied / purchased
- Reservation Redis State（future design only）
- WaitingList（future planned）
- Rate Limit
- Failure / Compensation
- Redis Performance

本 Skill 不替代：

- `AGENTS.md`
- `eventhub-development`
- `database-development`
- `rabbitmq-reliability`
- Inventory / Order 业务规则

实际业务事实以：

当前代码
+
适用 AGENTS.md
+
项目文档
+
MySQL 最终事实

为准。

---

# 1. 首先确定 Redis 在当前任务中的角色

开始任务前先判断 Redis 正在承担哪一种角色。

## Cache

用于读取加速。

例如：

- Event Cache
- Session Cache

最终事实在 MySQL。

## High-Concurrency Execution State

用于 FLASH 请求路径中的高并发原子判断。

例如：

- Session Stock
- occupied-users
- purchased-users
- Rate Limit

## Temporary Business State

用于具有明确生命周期的临时业务状态。

例如：

- Reservation Status（exact model not yet frozen）
- reservation-map（example only）
- WaitingList Qualification（planned; exact contract not yet frozen）

三种数据不能机械共享相同的：

- TTL
- 一致性策略
- 删除策略
- 故障恢复方式

---

# 2. Redis 与 MySQL 的系统边界

EventHub 中：

MySQL
=
Source of Truth

Redis
=
Cache + High-Concurrency Execution Layer

不要把 Redis 中的临时状态视为最终业务事实。

Redis 与 MySQL 可以存在短暂差异，
但系统必须能够通过既定：

- Retry
- Compensation
- Reconciliation

最终收敛。

不要把 Redis 演变成第二套业务主库。

---

# 3. 识别任务类型并读取对应 Reference

## Cache / Key / TTL

涉及：

- Event / Session Cache
- Cache Aside
- Key Design
- TTL
- Cache Penetration
- Cache Breakdown
- Cache Avalanche
- Serialization

读取：

`references/cache-and-keys.md`

## FLASH / Lua / Rate Limit

涉及：

- FLASH Stock
- Lua
- Atomic Reservation
- Fixed Window
- User / Session Rate Limit
- Distributed Lock 判断

读取：

`references/flash-lua-and-ratelimit.md`

## Reservation / WaitingList

涉及：

- occupied-users
- purchased-users
- reservation-map
- PROCESSING / SUCCESS / FAILED（example; persistence model not yet frozen）
- WaitingList implementation（not yet frozen）
- Qualification

读取：

`references/reservation-and-waiting.md`

## Failure / Compensation / Operations

涉及：

- Redis Unavailable
- Redis Timeout
- Compensation
- Reconciliation
- Big Key
- KEYS / SCAN
- Hot Key
- Redis Performance

读取：

`references/failure-and-operations.md`

一个任务可能需要多份 Reference。

只读取当前任务真正需要的内容。

---

# 4. Key 必须统一管理

Redis Key 不允许散落在业务代码中手工拼接。

统一通过 Key Builder / Constants 生成。

例如：

`RedisKeys.sessionStock(sessionId)`

`RedisKeys.occupiedUsers(sessionId)`

`RedisKeys.purchasedUsers(sessionId)`

`RedisKeys.reservationMap(sessionId)`

Java、Lua、测试和补偿逻辑必须共享相同 Key 语义。

EventHub V1 不以 Redis Cluster 为架构前提，
因此不要为了未来 Cluster 提前强制 Hash Tag / Slot 设计。

EventHub Phase 10 存在一个项目级冻结例外：

- `eventhub:flash:{sessionId}:meta`
- `eventhub:flash:{sessionId}:admissions`

Phase 10 的 four-script contract 会对同一 Session 的两个 Key 执行 multi-key Lua，
因此 `{sessionId}` 是明确的 same-slot hash tag 决策。这不构成“所有 Redis Key
都必须使用 hash tag”的通用规则。

---

# 5. FLASH 的原子边界由 Lua 保证

本节原子边界适用于 Phase 10 Batch 3 已 `COMPLETE / VERIFIED / FROZEN` 的当前
Production Redis admission primitives，并继续适用于后续 FLASH 演进。当前 reserve
result contract 区分：`P:<sameAdmissionId>` → `IDEMPOTENT_PENDING`，
`P:<differentAdmissionId>` → `ALREADY_PENDING`，`A:<orderId>` /
`R:<orderId>` → `ALREADY_ADMITTED`。

FLASH 不允许：

GET
→ Java 判断
→ DECR

决定一次用户是否获得抢票处理资格的关键 Redis 状态，
必须在同一个合理 Lua 原子边界内处理。

Lua 只负责：

高并发入口必须原子的最小业务单元。

不要把：

- 完整订单
- Ticket
- Notification
- 跨领域编排

塞入 Lua。

详细规则见：

`references/flash-lua-and-ratelimit.md`

---

# 6. 核心 Lua 作为资源文件维护

核心 Lua 位于：

`eventhub-inventory/src/main/resources/lua/`

或对应模块资源目录。

禁止将大型核心脚本写成 Java 内联字符串。

修改 Lua 时必须同步检查：

- KEYS
- ARGV
- Java 参数
- Return Protocol
- Key Builder
- Tests

---

# 7. occupied、purchased 和 Reservation 不得混淆

必须始终区分：

`occupied`

=
临时抢票占位

`purchased`

=
历史成功购买事实

`Reservation`

=
一次异步抢票请求及其处理状态

当前 Refund Foundation（`CURRENT / FROZEN / IMPLEMENTED`）可以恢复库存，
但不能因为退款删除历史 purchased 资格。

重复 Reservation 请求不得重复扣减库存。

详细规则见：

`references/reservation-and-waiting.md`

---

# 8. Redis 并发优先使用原子能力

遇到 Redis 并发问题时优先考虑：

- Lua
- Redis Atomic Command
- SET NX 的合适语义
- Set / ZSet / Hash 等数据结构能力
- MySQL Conditional UPDATE
- Database Unique Constraint

V1 不把：

Redisson Distributed Lock

作为默认核心并发方案。

只有现有原子模型无法合理解决真实互斥问题时，
才重新评估 Distributed Lock。

---

# 9. 故障策略必须区分 NORMAL 与 FLASH

FLASH Fail Fast / Fail Closed 是 Phase 10 `CURRENT PHASE / FROZEN DESIGN /
NOT_IMPLEMENTED` 的新请求故障 Contract。

NORMAL：

核心库存竞争在 MySQL，
Redis 辅助能力故障时可以根据既定方案有限降级。

FLASH：

Redis 是保护 MySQL 的高并发入口。

Redis 不可用时默认：

Fail Fast。

禁止：

Redis Down
→ FLASH 全部直打 MySQL

详细故障与补偿规则见：

`references/failure-and-operations.md`

---

# 10. Redis 修改后的验证

根据任务范围进行最小必要验证。

## Cache

验证：

- Hit / Miss
- MySQL Fallback
- TTL
- Invalidation

## Lua / FLASH

验证：

- Success
- Sold Out
- Duplicate Request
- Already Purchased
- Sales Time
- Stock >= 0
- Atomic Result

## Reservation / WaitingList

验证：

- occupied / purchased 语义
- Reservation 重复请求
- Qualification
- Waiting Order
- Expiration

## Failure

根据任务验证：

- Redis Timeout
- Redis Unavailable
- Duplicate Compensation
- Reconciliation

核心 Lua 和 Redis 数据结构行为优先使用真实 Redis 验证。

---

# 11. 性能优化必须来自真实证据

不要根据猜测提前复杂化 Redis 设计。

根据需要观察：

- Latency
- QPS
- Lua Duration
- Round Trip
- Hot Key
- Big Key
- Redis CPU
- JMeter P95 / P99

流程：

发现瓶颈
→ 定位原因
→ 修改
→ 再验证。

---

# 12. 文档维护

Redis 重要事实变化时检查：

`docs/specification/eventhub-v1.md`、`docs/database/schema.md` 与
`docs/development/progress.md`。只有相关 middleware 文档真实存在时再读取。

需要更新的典型内容包括：

- Key / TTL
- Cache Strategy
- FLASH Lua
- Stock Model
- Reservation State
- WaitingList
- Rate Limit
- Failure Strategy
- Compensation / Reconciliation

代码说明：

现在怎么实现。

docs 说明：

为什么这样设计。

# EventHub Order 模块开发规则

> 版本：V1.0
>
> 本文件适用于 `eventhub-order`。
> 本文件补充仓库根目录 `AGENTS.md`。
> 未特别规定的事项继续遵守仓库级规则。
>
> Order 是 EventHub 的核心一致性模块。
> 修改订单、Reservation、Outbox 或 MQ Consumer 时，
> 状态机正确性、幂等性和消息可靠性优先于代码简洁性。

---

# 1. 模块职责

`eventhub-order` 当前负责：

- TicketOrder
- NORMAL Reserve 与 `WAIT_PAY` 订单创建
- 支付、取消、超时状态转换
- 订单状态机
- NORMAL Reserve / Pay / Cancel / Timeout 的本地事务编排

未来 `PAID → REFUNDED`、退款幂等与 `sold → available` 为
`FUTURE / FROZEN / NOT_IMPLEMENTED`；FLASH 的 Lua → Reservation → Outbox →
RabbitMQ → Consumer → MySQL 主链路及 At-Least-Once + Idempotent Consumer 也为
`FUTURE / FROZEN / NOT_IMPLEMENTED`。Refund 事务所有者、Reservation 精确
持久化模型 / `reservation_id`、消息拓扑与精确幂等 Key 尚未冻结。

本模块不负责：

- 实际库存字段变更
- Redis FLASH 库存
- WaitingList
- Ticket 最终业务能力
- HTTP Controller
- 未经 Frozen Contract 授权的其他跨领域流程编排

跨领域流程默认由 `eventhub-app` 的 ApplicationService 编排。当前 Phase 6
NORMAL Order Lifecycle 是已冻结窄例外，由本模块拥有 Reserve、Pay、Cancel、
Timeout 的事务编排。

---

# 2. 订单状态机

当前已实现的合法状态转换只有：

`WAIT_PAY → PAID`

`WAIT_PAY → CANCELLED`

`WAIT_PAY → CLOSED`

未来已冻结但尚未实现的转换：

`PAID → REFUNDED`（`FUTURE / FROZEN / NOT_IMPLEMENTED`）

Phase 3 第一版 Schema Baseline 只包含
`WAIT_PAY`、`PAID`、`CLOSED`、`CANCELLED`；实现退款前必须通过新
Migration 扩展状态 `CHECK`，并重新评估 `purchase_guard` 和 Ticket
唯一约束，不能把后续语义倒灌进第一版 Migration。

当前持久终态：

- `CANCELLED`
- `CLOSED`

未来 `REFUNDED` 为退款终态，但不是当前持久状态。

禁止未定义状态转换，例如：

- `CANCELLED → PAID`
- `CLOSED → PAID`
- `REFUNDED → PAID`
- `CANCELLED → WAIT_PAY`
- `CLOSED → WAIT_PAY`

不要通过无条件 `setStatus` 绕过状态机。

状态变化必须显式体现：

当前状态
→ 合法条件
→ 目标状态

---

# 3. WAIT_PAY 与并发竞争

有效订单创建后初始状态为：

`WAIT_PAY`

支付窗口：

15 分钟。

只有 `WAIT_PAY` 可以执行：

- Pay
- Cancel
- Timeout

支付、取消和超时存在竞争关系。

同一订单只能有一个竞争操作成功获得：

`WAIT_PAY`

状态转换资格。

不得出现：

支付成功 + 取消成功

支付成功 + 超时成功

取消成功 + 超时成功

同时成立。

存在并发竞争的状态转换优先使用数据库条件 UPDATE，例如：

`WHERE status = WAIT_PAY`

并通过 `affectedRows` 判断本次操作是否真正完成状态转换。

避免：

SELECT
→ Java 判断
→ 无条件 UPDATE

这种存在并发窗口的实现。

---

# 4. 支付幂等

支付只允许：

`WAIT_PAY → PAID`

重复支付请求不得重复执行有效支付状态转换及其后续业务副作用。

第一次支付成功后，后续重复请求应：

- 返回已有结果
- 或明确表示订单已经处理

不得再次执行有效支付流程。

---

# 5. 取消与超时幂等

取消只允许：

`WAIT_PAY → CANCELLED`

超时关闭只允许：

`WAIT_PAY → CLOSED`

重复取消、重复超时扫描、重复超时消息不得重复产生业务副作用。

订单超时必须满足支付期限已经到达。

V1 可以使用：

`@Scheduled`

扫描超时订单。

后续即使改为 RabbitMQ 延迟消息：

调度机制可以变化，

但：

`WAIT_PAY → CLOSED`

的业务语义和幂等要求不得变化。

---

# 6. 退款规则

本节为 `FUTURE / FROZEN / NOT_IMPLEMENTED` 的业务 Contract，不描述当前
Production behavior。

退款只允许：

`PAID → REFUNDED`

退款必须幂等。

重复退款不得重复执行退款后的业务副作用。

退款成功后：

`REFUNDED`

为终态。

退款会恢复库存：

`sold → available`

但：

退款不会恢复用户再次参与同一 Session 抢票的资格。

用户一旦曾成功支付并获得该 Session 的 Ticket，
即视为已经成功购买过该 Session。

必须区分：

库存是否恢复

与：

历史购买资格是否恢复

两者不是同一个业务概念。

Refund API、事务所有者、Ticket 精确退款状态 / 失效方式与 MQ 集成仍为
`FUTURE / NOT_YET_FROZEN / NOT_IMPLEMENTED`。

---

# 7. Order 与 Inventory 边界

Order 负责：

订单状态。

Inventory 负责：

库存状态。

当前 Reserve、Pay、Cancel 与 Timeout 由本模块 Service 建立 Local Transaction，
先完成 Order INSERT / Conditional UPDATE，再通过 Inventory Public API 执行
`available → locked`、`locked → sold` 或 `locked → available`。Inventory mutation
以 `MANDATORY` 加入外层事务。

未来 Refund 的事务所有者尚未冻结；不得预设 `RefundApplicationService`、
Order Service 或 Ticket Service 为所有者。

Order 模块不得直接访问 Inventory 的：

- Mapper
- Repository
- Redis 实现
- 内部 Service 实现

---

# 8. Reservation

Reservation business concept 属于 `FUTURE / FROZEN / NOT_IMPLEMENTED`；精确
DB 表、字段、状态持久化模型、`reservation_id` 与幂等 Key 为
`FUTURE / NOT_YET_FROZEN / NOT_IMPLEMENTED`。

Reservation 表示：

异步抢票请求及其处理过程。

TicketOrder 表示：

最终订单事实。

两者不得混为同一业务对象。

以下状态仅为未来方向示例，不构成当前持久化模型 Freeze：

- `PROCESSING`
- `SUCCESS`
- `FAILED`

相同业务请求的重复处理不得创建多个有效 Reservation / Order 结果；具体业务
标识与约束在 FLASH 实现阶段冻结。

---

# 9. FLASH 创建订单幂等

本节主链路为 `FUTURE / FROZEN / NOT_IMPLEMENTED`。Reservation 精确持久化
模型、`reservation_id` 与精确幂等 Key 尚未冻结。

FLASH 核心链路：

Redis Lua
→ Reservation
→ RabbitMQ
→ Order Consumer
→ MySQL

RabbitMQ 采用 At-Least-Once。

因此必须假设：

同一消息可能被处理多次。

相同业务请求不得创建多个订单；数据库最终安全线必须在精确业务 Key 冻结后
通过后续 Migration 设计，当前不得预设 `UNIQUE(reservation_id)`。

Consumer 的目标不是保证：

“消息绝不重复”

而是保证：

“消息即使重复，最终业务结果仍然正确”。

---

# 10. MQ Consumer

本节描述 FLASH 未来冻结的 At-Least-Once + Idempotent Consumer 方向，当前
尚未实现，且消息拓扑与精确幂等 Key 尚未冻结。

Order Consumer 必须正确处理：

- 重复投递
- Consumer 异常
- Consumer 重启
- ACK 失败
- Retry
- DLQ
- 数据库成功但 ACK 前进程异常

不得依赖：

RabbitMQ 只会投递一次。

业务成功以后才能进行正确 ACK。

业务失败时不得错误 ACK 导致消息永久丢失。

重复消费不得产生：

- 重复 Order
- 重复有效状态转换
- 重复业务副作用

---

# 11. Transactional Outbox

Transactional Outbox 是 FLASH 可靠发布的
`FUTURE / FROZEN / NOT_IMPLEMENTED` 方向；精确 Schema 与状态机尚未冻结。

需要同时保证：

业务数据库状态

和：

待发送消息

可靠落库时，使用 Transactional Outbox。

业务数据和对应 Outbox 记录必须在：

同一个 MySQL Local Transaction

中提交。

禁止使用：

数据库提交成功
→ 直接发送 MQ
→ 没有失败恢复机制

作为可靠消息方案。

Outbox Publisher 必须允许重复发送。

因此下游 Consumer 必须保持幂等。

不要把 Publisher Confirm 等同于最终业务成功。

最终业务事实仍以 MySQL 为准。

---

# 12. 数据库安全线

Phase 3 Baseline 中 `ticket_order` 至少保持：

`UNIQUE(order_no)`

`UNIQUE(user_id, session_id, purchase_guard)`

其中 generated `purchase_guard` 用于阻止同一用户、同一 Session 的重复
`WAIT_PAY` / `PAID` Order，同时允许 `CLOSED` / `CANCELLED` 后重新下单。

FLASH Reservation concept 已冻结，但精确持久化字段与数据库唯一安全线尚未
冻结；不得提前要求 `UNIQUE(reservation_id)`。

核心索引支持：

用户订单查询：

`(user_id, created_at)`

Session 逻辑关联查询：

`(session_id)`

`(session_id, status)` 当前暂缓，只有真实查询路径需要时再评估。

超时扫描：

`(status, expire_at)`

修改相关 Schema 时必须确认：

- 当前已冻结的幂等约束仍然存在；未来 FLASH 约束以其实现阶段 Freeze 为准
- 超时扫描索引仍然有效
- 主要查询路径仍然合理

---

# 13. 失败与补偿

Order 核心逻辑必须考虑：

- 重复支付
- 重复取消
- 重复退款
- 支付与取消竞争
- 支付与超时竞争
- MQ 重复投递
- Consumer 执行异常
- Consumer 写库成功但 ACK 前异常
- Outbox 重复发送
- Reservation 长时间 PROCESSING
- 重复补偿

不要只处理 Happy Path。

Redis、RabbitMQ、MySQL 之间出现异常时，
遵循仓库级最终一致性方案：

- Retry
- Idempotency
- Compensation
- Reconciliation

不要自行引入新的分布式事务方案。

---

# 14. 核心修改后的检查

修改以下内容时：

- 订单状态机
- 支付
- 取消
- 超时
- 退款
- Reservation
- MQ Consumer
- Outbox
- 补偿

必须主动检查：

1. 状态转换是否合法。
2. 重复调用是否幂等。
3. 并发状态竞争是否只能成功一次。
4. MQ 重复消费是否安全（未来 FLASH）。
5. 已冻结的未来业务 Key 是否仍能保证唯一业务结果；当前不得预设 `reservationId`。
6. 失败路径是否可能产生部分成功。
7. 跨模块副作用是否由对应 Frozen transaction owner 正确编排；当前 NORMAL 为 Order Service。
8. 数据库唯一约束是否仍然有效。

---

# 15. 最低测试要求

根据当前实现与修改范围至少覆盖：

- `WAIT_PAY → PAID`
- `WAIT_PAY → CANCELLED`
- `WAIT_PAY → CLOSED`
- 非法状态转换
- 重复支付
- 重复取消
- 支付与取消竞争
- 支付与超时竞争

未来 Refund / FLASH / MQ / Outbox 进入实际实现 Scope 后，才按届时冻结的
Contract 增加 `PAID → REFUNDED`、重复退款、重复消息、Consumer、业务 Key
幂等与 Outbox 重复发送测试。

至少必须证明：

重复支付不会执行多次有效支付状态转换。

重复取消不会导致库存恢复多次。

---

# 16. 文档维护

以下设计变化时检查并更新对应文档：

- 订单状态机
- Reservation
- 支付 / 取消 / 超时 / 退款
- RabbitMQ 消息模型
- Transactional Outbox
- Consumer 幂等
- Retry / DLQ
- Compensation / Reconciliation

优先维护：

`docs/specification/eventhub-v1.md`

`docs/database/schema.md`

`docs/development/progress.md`

本文件只维护 Order 模块长期稳定的不变量和行为约束。

RabbitMQ、Outbox 和最终一致性的详细原理与学习说明应维护在 docs 或对应 Skill 中。

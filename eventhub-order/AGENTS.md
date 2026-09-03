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

`eventhub-order` 负责：

- TicketOrder
- ReservationRequest
- 订单创建
- 支付、取消、超时、退款状态转换
- 订单状态机
- FLASH 异步订单创建
- Order 相关消息
- Transactional Outbox
- Consumer 幂等

本模块不负责：

- 实际库存字段变更
- Redis FLASH 库存
- WaitingList
- Ticket 最终业务能力
- HTTP Controller
- 跨领域流程编排

跨领域流程由 `eventhub-app` 中的 ApplicationService 编排。

---

# 2. 订单状态机

合法状态转换只有：

`WAIT_PAY → PAID`

`WAIT_PAY → CANCELLED`

`WAIT_PAY → TIMEOUT`

`PAID → REFUNDED`

终态：

- `CANCELLED`
- `TIMEOUT`
- `REFUNDED`

禁止未定义状态转换，例如：

- `CANCELLED → PAID`
- `TIMEOUT → PAID`
- `REFUNDED → PAID`
- `CANCELLED → WAIT_PAY`
- `TIMEOUT → WAIT_PAY`

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

超时只允许：

`WAIT_PAY → TIMEOUT`

重复取消、重复超时扫描、重复超时消息不得重复产生业务副作用。

订单超时必须满足支付期限已经到达。

V1 可以使用：

`@Scheduled`

扫描超时订单。

后续即使改为 RabbitMQ 延迟消息：

调度机制可以变化，

但：

`WAIT_PAY → TIMEOUT`

的业务语义和幂等要求不得变化。

---

# 6. 退款规则

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

---

# 7. Order 与 Inventory 边界

Order 负责：

订单状态。

Inventory 负责：

库存状态。

支付：

`PaymentApplicationService`

编排：

Order API
→ `WAIT_PAY → PAID`

Inventory API
→ `locked → sold`

取消：

`CancellationApplicationService`

编排：

Order API
→ `WAIT_PAY → CANCELLED`

Inventory API
→ `locked → available`

退款：

`RefundApplicationService`

编排：

Order API
→ `PAID → REFUNDED`

Inventory API
→ `sold → available`

必要时再协调 Ticket API。

Order 模块不得直接访问 Inventory 的：

- Mapper
- Repository
- Redis 实现
- 内部 Service 实现

---

# 8. Reservation

FLASH 抢票使用：

`reservationId`

作为异步请求的重要业务标识。

Reservation 表示：

异步抢票请求及其处理过程。

TicketOrder 表示：

最终订单事实。

两者不得混为同一业务对象。

Reservation 对外主要状态：

- `PROCESSING`
- `SUCCESS`
- `FAILED`

SUCCESS 时应能够关联：

`orderNo`

相同业务请求的重复处理不得创建多个有效 Reservation / Order 结果。

---

# 9. FLASH 创建订单幂等

FLASH 核心链路：

Redis Lua
→ Reservation
→ RabbitMQ
→ Order Consumer
→ MySQL

RabbitMQ 采用 At-Least-Once。

因此必须假设：

同一消息可能被处理多次。

相同：

`reservationId`

不得创建多个订单。

数据库层保持：

`UNIQUE(reservation_id)`

作为最终幂等安全线之一。

Consumer 的目标不是保证：

“消息绝不重复”

而是保证：

“消息即使重复，最终业务结果仍然正确”。

---

# 10. MQ Consumer

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

`ticket_order` 至少保持：

`UNIQUE(order_no)`

`UNIQUE(reservation_id)`

核心索引支持：

用户订单查询：

`(user_id, created_at)`

Session 状态查询：

`(session_id, status)`

超时扫描：

`(status, expire_time)`

修改相关 Schema 时必须确认：

- reservationId 幂等约束仍然存在
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
4. MQ 重复消费是否安全。
5. reservationId 是否仍能保证唯一业务结果。
6. 失败路径是否可能产生部分成功。
7. 跨模块副作用是否由 ApplicationService 正确编排。
8. 数据库唯一约束是否仍然有效。

---

# 15. 最低测试要求

根据修改范围至少覆盖：

- `WAIT_PAY → PAID`
- `WAIT_PAY → CANCELLED`
- `WAIT_PAY → TIMEOUT`
- `PAID → REFUNDED`
- 非法状态转换
- 重复支付
- 重复取消
- 重复退款
- 支付与取消竞争
- 支付与超时竞争
- MQ 重复消息
- Consumer 异常
- reservationId 幂等
- Outbox 重复发送

至少必须证明：

重复 MQ 消息不会创建重复订单。

重复支付不会执行多次有效支付状态转换。

重复取消不会导致库存恢复多次。

重复退款不会执行多次有效退款。

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

`docs/architecture/reservation-flow.md`

`docs/architecture/consistency.md`

`docs/middleware/rabbitmq.md`

本文件只维护 Order 模块长期稳定的不变量和行为约束。

RabbitMQ、Outbox 和最终一致性的详细原理与学习说明应维护在 docs 或对应 Skill 中。
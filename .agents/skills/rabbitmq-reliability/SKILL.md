---
name: rabbitmq-reliability
description: >
  用于 EventHub 中涉及 RabbitMQ、异步订单、Transactional Outbox、
  Publisher Confirm、Return、Manual ACK、Consumer 幂等、Retry、
  DLQ、延迟消息以及消息可靠性的任务。
  当功能涉及可靠发送、重复消费、消息失败恢复、MySQL 与 RabbitMQ
  最终一致性或异步业务链路时使用。
---

# EventHub RabbitMQ Reliability

本 Skill 定义 EventHub RabbitMQ 任务的标准执行流程。

主要处理：

- Message Contract
- Transactional Outbox
- Producer Reliability
- Publisher Confirm / Return
- Consumer Idempotency
- Manual ACK
- Retry / DLQ
- Delay / Timeout Message
- RabbitMQ Failure
- Messaging Observability

本 Skill 不替代：

- `AGENTS.md`
- `eventhub-development`
- `database-development`
- `redis-concurrency`
- Order 状态机
- ApplicationService 跨领域编排

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

# 1. 首先明确为什么需要 MQ

开始 RabbitMQ 任务前先确认：

MQ 在当前 Use Case 中解决什么问题。

EventHub 典型用途：

- FLASH 削峰
- 异步创建订单
- 延迟超时
- Notification
- 非核心流程解耦

如果同步调用已经：

- 简单
- 可靠
- 延迟可接受
- 不需要削峰

不要为了展示 RabbitMQ 强制异步化。

引入 MQ 意味着必须同时考虑：

- Duplicate Delivery
- Idempotency
- Retry
- DLQ
- Failure Recovery
- Observability

---

# 2. 可靠性目标

EventHub 不追求 Transport Level：

Exactly Once。

目标：

At-Least-Once
+
Idempotent Consumer

因此系统必须接受：

同一业务消息可能被：

- 重复发送
- 重复投递
- 重复消费

真正需要保证的是：

重复发生以后，
最终业务结果仍然正确。

---

# 3. 识别任务类型并读取对应 Reference

## Outbox / Producer

涉及：

- Message Envelope
- Business Key
- Transactional Outbox
- Outbox Publisher
- Publisher Confirm
- Return Callback
- Persistent Message
- Producer Failure

读取：

`references/outbox-and-producer.md`

## Consumer / Idempotency / ACK

涉及：

- Manual ACK
- Duplicate Delivery
- Consumer Crash
- reservationId
- DuplicateKeyException
- Consumer Idempotency
- Consumer Concurrency

读取：

`references/consumer-reliability.md`

## Retry / DLQ

涉及：

- Consumer Failure
- Retry
- Requeue
- Poison Message
- DLQ
- DLQ Replay

读取：

`references/retry-and-dlq.md`

## Timeout / Operations

涉及：

- WAIT_PAY Timeout
- TTL + DLX
- Notification
- MQ Unavailable
- Outbox Backlog
- Queue Backpressure
- Prefetch
- Monitoring

读取：

`references/timeout-and-operations.md`

一个任务可能需要多份 Reference。

只读取当前任务真正涉及的内容。

---

# 4. Message Identity 与 Business Identity 必须区分

核心消息统一使用 Message Envelope。

至少包含：

- messageId
- messageType
- occurredAt
- businessKey
- traceId（允许为空）
- payload

必须区分：

`messageId`

和：

业务唯一标识。

例如 FLASH Order：

`reservationId`

才是 Order Create Consumer 的核心业务幂等 Key。

同一个业务请求可能因为 Outbox Retry 被多次发送。

因此不能只依赖：

messageId

保证业务唯一。

---

# 5. 需要可靠关联 DB 与 MQ 时使用 Outbox

如果业务数据库变化和消息产生必须可靠关联：

Business Data
+
Outbox Record

必须进入同一个：

MySQL Local Transaction。

不要实现：

MySQL COMMIT
→ 直接 Send MQ
→ 失败以后没有恢复记录

作为核心可靠消息方案。

详细规则见：

`references/outbox-and-producer.md`

---

# 6. Producer 成功不等于业务成功

必须区分：

Publisher Confirm

Return Callback

Consumer ACK。

它们分别解决消息链路的不同阶段。

任何一个：

Confirm ACK

都不能证明：

Consumer 已完成最终业务。

端到端业务结果最终仍需要由：

Consumer
+
业务状态
+
MySQL

确认。

---

# 7. Consumer 必须幂等

核心 Consumer 使用：

Manual ACK。

基本顺序：

Receive
→ Business Processing
→ Business Success
→ ACK

Consumer 必须能够面对：

数据库已经 COMMIT
+
ACK 前 JVM Crash
+
消息再次投递

这种情况。

重复消息不得产生重复有效业务结果。

Order Create 当前优先使用：

reservationId
+
业务状态
+
`UNIQUE(reservation_id)`

作为幂等安全线。

V1 不默认建立通用：

`consumed_message`

表。

详细规则见：

`references/consumer-reliability.md`

---

# 8. Retry 必须有限

禁止：

catch Exception
→ requeue=true
→ 无限循环

Consumer 必须区分：

- Transient Failure
- Permanent Failure
- Idempotent Success

临时错误：

可以有限 Retry。

持续失败或不可恢复消息：

最终进入 DLQ。

具体 Retry 次数通过配置和实际业务决定，
不在本 Skill 中固定。

详细规则见：

`references/retry-and-dlq.md`

---

# 9. Timeout Message 不能绕过状态机

RabbitMQ Timeout Message 的语义是：

“现在应该检查该订单是否需要超时。”

不是：

“强制把订单改成 TIMEOUT。”

合法转换仍然必须是：

WAIT_PAY
→ TIMEOUT

已经：

PAID
CANCELLED
TIMEOUT

的订单收到延迟消息：

不得产生新的超时业务副作用。

V1 延迟消息优先：

TTL
+
DLX

不默认引入 Delayed Message Plugin。

详细规则见：

`references/timeout-and-operations.md`

---

# 10. RabbitMQ 修改后的验证

根据任务范围进行最小必要验证。

## Producer

检查：

- Outbox Transaction
- Publish
- Confirm
- Return
- MQ Unavailable
- Duplicate Publish

## Consumer

检查：

- Normal Consume
- Duplicate Delivery
- DB Commit Before ACK Crash
- Idempotency
- Invalid Message

## Failure

检查：

- Retry
- Retry Exhausted
- DLQ
- Replay

## Timeout

检查：

WAIT_PAY
→ TIMEOUT

以及：

PAID + Timeout Message

不会错误变成 TIMEOUT。

RabbitMQ 可靠性关键路径优先使用真实 Broker 验证。

---

# 11. 不宣称 Exactly Once

README、docs、代码注释和最终汇报中：

不得写：

“RabbitMQ 保证 Exactly Once。”

准确表达：

Transactional Outbox
+
Producer Reliability
+
At-Least-Once
+
Idempotent Consumer
+
Database Constraints
+
Retry / DLQ / Compensation

共同保证：

消息允许重复，
但最终业务结果可靠并最终一致。

---

# 12. 文档维护

RabbitMQ 重要设计变化时检查：

`docs/middleware/rabbitmq.md`

以及必要架构文档。

典型变化：

- Message Contract
- Topology
- Outbox
- Confirm / Return
- ACK
- Idempotency
- Retry / DLQ
- Timeout
- Failure Strategy
- Monitoring

代码说明：

系统现在怎么实现。

docs 说明：

为什么这样设计。
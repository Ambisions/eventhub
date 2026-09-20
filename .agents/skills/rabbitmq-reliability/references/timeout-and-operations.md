# EventHub Timeout 与 RabbitMQ Operations

仅当任务涉及：

- WAIT_PAY Timeout
- Future timeout trigger mechanism（TTL / DLX 等尚未冻结）
- Notification
- RabbitMQ Failure
- Outbox Backlog
- Queue Backpressure
- Prefetch
- Metrics / Logging

时读取。

---

# 1. WAIT_PAY Timeout

WAIT_PAY 支付窗口：

15 分钟。

早期实现允许：

@Scheduled

未来将 Timeout trigger 改为 MQ 属于 `FUTURE / PLANNED`。TTL / DLX、Delayed
Message Plugin 或其他机制均为 `NOT_YET_FROZEN`，本 Reference 不预设队列拓扑。

V1 不默认引入：

RabbitMQ Delayed Message Exchange Plugin。

---

# 2. Timeout Message 只是 Trigger

Timeout Message 到达：

只表示：

现在应该执行一次超时检查。

不能直接认为：

订单必须进入超时终态。

合法转换仍然：

WAIT_PAY
→ CLOSED

必须继续检查：

- Current Status
- expireTime

已经：

PAID
CANCELLED
CLOSED

的订单不得被延迟消息再次产生副作用。

---

# 3. Timeout Idempotency

同一个 Timeout Message 可能重复。

业务最终仍依赖：

Conditional State Transition

例如：

WHERE status = WAIT_PAY

保证只能有一次有效状态变化。

MQ 不能取代 Order State Machine。

---

# 4. Notification

Notification 属于非核心业务副作用。

通知失败通常不应回滚：

- Order Success
- Payment Success
- Ticket Success

Notification 可以拥有独立：

- Queue
- Retry
- DLQ

同时仍然要考虑：

Duplicate Notification。

---

# 5. RabbitMQ Unavailable

使用 Outbox 时：

RabbitMQ 暂时 Down，

Business Data
+
Outbox

仍可按业务事务设计提交。

MQ 恢复后：

Publisher 继续发送。

但用户对外状态必须如实表达当前业务仍可能：

PROCESSING。

不能 catch MQ Error 后伪装成端到端完成。

---

# 6. Outbox Backlog

RabbitMQ 长时间不可用时：

Outbox Pending 会增长。

至少关注：

- Pending Count
- Oldest Pending Age
- Retry Count
- Publish Failure Rate

MQ 恢复后：

不要无控制瞬间倾泻所有积压。

恢复速度需要考虑：

Broker
+
Consumer
+
MySQL

下游容量。

---

# 7. Backpressure

关注：

- Publish Rate
- Consume Rate
- Queue Depth
- Unacked
- Consumer Processing Time
- MySQL Capacity

Queue 积压：

不代表只需要增加更多 Consumer。

真正瓶颈可能在：

- MySQL
- CPU
- IO
- Redis
- Network

---

# 8. Prefetch

Prefetch 会影响：

- Throughput
- Fairness
- Unacked Count
- Crash 后 Redelivery Volume

它属于：

Performance Parameter

而不是：

Correctness Mechanism。

需要结合：

- Consumer Concurrency
- Processing Time
- Database Capacity
- Message Size

通过真实测试调整。

---

# 9. Logging

消息日志至少根据需要关联：

- messageId
- messageType
- businessKey
- frozen business key / orderNo
- retryCount
- traceId

不要默认记录：

- Password
- Token
- 完整敏感 Payload

目标是能够追踪：

消息什么时候产生？
发送了几次？
消费了几次？
最终业务结果是什么？

---

# 10. Metrics

根据当前阶段关注：

- Publish Success / Failure
- Confirm NACK
- Returned Message
- Queue Depth
- Consumer Rate
- Unacked
- Retry
- DLQ
- Outbox Pending
- Oldest Outbox Age

Broker Alive：

不等于：

消息链路健康。

---

# 11. Operations Verification

根据任务验证：

- Timeout State Guard
- Duplicate Timeout
- RabbitMQ Down
- Outbox Accumulation
- Recovery
- Queue Backlog
- Consumer Concurrency
- Prefetch Change

性能参数调整必须依赖：

真实 Benchmark

才能形成性能结论。

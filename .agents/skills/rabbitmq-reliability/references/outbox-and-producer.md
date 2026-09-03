# EventHub Outbox 与 Producer Reliability

仅当任务涉及：

- Message Contract
- Transactional Outbox
- Outbox Publisher
- Publisher Confirm
- Return Callback
- Producer Failure

时读取。

---

# 1. Message Envelope

V1 核心消息统一包含：

- messageId
- messageType
- occurredAt
- businessKey
- traceId（允许为空）
- payload

消息体只携带：

完成消费任务需要的最小稳定信息。

不要直接发送完整大型数据库 Entity。

Message Contract 变化时同步检查：

- Producer
- Consumer
- Outbox
- Tests
- Docs

---

# 2. Message ID 与 Business Key

`messageId`

表示一条消息实例。

`businessKey`

表示消息对应的业务事实。

例如：

Order Create：

businessKey = reservationId

同一个 Business Key 可以因为：

Outbox Retry

产生多次 Message Delivery。

Consumer Idempotency 应围绕业务身份设计。

---

# 3. Transactional Outbox

业务数据库状态和消息产生必须可靠关联时：

Business Data
+
Outbox Record

在同一个：

MySQL Local Transaction

中提交。

例如：

Reservation 持久化
+
OrderCreate Outbox

如果业务要求两者共同成立：

必须共同 COMMIT / ROLLBACK。

---

# 4. Outbox 解决的 Crash Window

典型问题：

Business COMMIT
→ JVM Crash
→ MQ 尚未发送

存在 Outbox 后：

Publisher 可以恢复发送。

但是仍可能出现：

MQ 已收到
→ Outbox 尚未标 SENT
→ Publisher Crash

应用恢复后：

再次发送同一消息。

因此：

Outbox 不能消除 Duplicate。

Consumer 仍必须幂等。

---

# 5. Outbox Record

根据实际实现至少考虑：

- messageId
- messageType
- businessKey
- payload
- status
- retryCount
- createdAt

必要时：

- nextRetryTime
- sentAt
- lastError

不要为了未来假设无限增加字段。

---

# 6. Outbox Publisher

Publisher 负责：

Pending Outbox
→ RabbitMQ
→ Update Outbox State

必须允许：

- 重复扫描
- 重复发送
- 应用重启
- Publish Crash

不能假设：

每个 Outbox 只发送一次。

Publisher 并发执行时还应防止无意义重复抢占同一批任务，
具体方式按当前实现设计。

---

# 7. Publisher Confirm

Confirm 表示：

Broker 是否接受 Publish。

ACK：

Broker 接受。

NACK：

对应 Outbox 不得错误标记为最终成功，
需要进入可恢复状态。

Confirm：

不等于 Consumer Success。

---

# 8. Return Callback

使用 mandatory routing 时，

Return 表示：

消息到达 Exchange

但无法正确路由到 Queue。

必须记录：

- messageId
- businessKey
- exchange
- routingKey
- return reason

不能静默丢弃 Returned Message。

---

# 9. Durable / Persistent

核心业务根据当前 topology 使用合理：

- Durable Exchange
- Durable Queue
- Persistent Message

Broker Restart 后的可靠性设计不能依赖临时 Queue / Message。

具体名称和 topology 以当前配置与 docs 为准。

---

# 10. Producer Failure Verification

根据任务验证：

- Business + Outbox 同事务
- Transaction Rollback
- Normal Publish
- Confirm ACK
- Confirm NACK
- Routing Failure
- RabbitMQ Unavailable
- Publisher Duplicate
- Publisher Restart

Outbox Retry 不得破坏最终业务幂等性。
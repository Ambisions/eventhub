# EventHub Consumer Reliability

仅当任务涉及：

- Manual ACK
- Duplicate Delivery
- Consumer Idempotency
- Consumer Crash
- reservationId
- Duplicate Key
- Consumer Concurrency

时读取。

---

# 1. Manual ACK

核心 Consumer 使用：

Manual ACK。

顺序必须是：

Receive
→ Business Process
→ Business Success
→ ACK

禁止：

Receive
→ ACK
→ Business Process

否则业务失败后消息可能永久丢失。

---

# 2. ACK 前 Crash

必须考虑：

Message Received
→ DB COMMIT
→ Before ACK
→ JVM Crash

RabbitMQ 可能重新投递。

因此 Consumer 必须允许：

同一业务请求再次执行。

DB Success：

不代表消息不会再次出现。

---

# 3. Consumer Idempotency

优先使用自然业务 Key。

Order Create：

reservationId

数据库：

`UNIQUE(reservation_id)`

典型处理：

Receive
→ Resolve businessKey
→ Check Existing Business Result
→ Try Process
→ DB Constraint Safety Line
→ ACK Existing / New Correct Result

---

# 4. V1 不建 consumed_message

Order Create 已存在：

reservationId
+
Unique Constraint

所以 V1 不默认建立：

`consumed_message`

或：

`mq_consumed_message`

如果未来某个 Consumer：

没有可靠自然 Business Key

再专项评估通用消费记录机制。

---

# 5. Duplicate Key

遇到：

DuplicateKeyException

不能机械处理。

例如：

相同 reservationId 已有合法 Order：

属于可能的正常 Duplicate Delivery，
可按幂等成功处理。

其他 Unique Conflict：

可能表示真实数据异常。

禁止：

所有 DuplicateKey 都吞掉。

也禁止：

所有 DuplicateKey 都 Retry 到 DLQ。

必须识别触发的是哪一个业务 Constraint。

---

# 6. ACK Decision

## Business Success

ACK。

## Duplicate，但最终业务事实正确

ACK。

## Temporary Failure

进入有限 Retry。

## Invalid / Permanent Message Failure

不要无限重投。

进入：

DLQ
或明确失败路径。

---

# 7. Consumer 并发

优先通过：

Spring AMQP Listener Container Concurrency

控制 Consumer 并发。

不要默认：

Rabbit Listener
→ Custom ThreadPool
→ Business

形成第二层队列。

否则会增加：

- ACK 时序
- Error Handling
- Backpressure
- MDC
- ThreadLocal
- Shutdown

复杂度。

---

# 8. Consumer Reliability Verification

至少根据任务覆盖：

- Normal Message
- Duplicate Message
- Same reservationId Multiple Delivery
- Consumer Exception
- Consumer Restart
- DB Commit Before ACK Crash
- Invalid Message

Order Create 最终必须证明：

同一个 reservationId

只能形成一个合法 Order。
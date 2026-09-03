# EventHub RabbitMQ Retry 与 DLQ

仅当任务涉及：

- Consumer Failure
- Retry
- Requeue
- Poison Message
- DLQ
- Replay

时读取。

---

# 1. Retry 必须有限

禁止：

Exception
→ NACK requeue=true
→ Immediate Redelivery
→ Exception
→ Infinite Loop

Retry 必须：

- Bounded
- Observable

具体次数根据：

- Message Type
- Exception Type
- Business Cost
- Benchmark

配置。

Skill 不硬编码统一次数。

---

# 2. Transient Failure

例如：

- 临时 DB Connection Failure
- 短暂 Network Problem
- 临时 Infrastructure Failure

可能适合：

Retry。

但仍必须存在上限。

---

# 3. Permanent Failure

例如：

- Invalid Message Contract
- Missing Required Field
- Unsupported Message Type
- Unrecoverable Deserialization Failure

重复消费不会自动变正确。

不应无限 Retry。

---

# 4. Hot Requeue Loop

禁止：

NACK requeue=true

造成消息立即重新消费并再次失败。

Hot Loop 可能导致：

- CPU 浪费
- Consumer 被占满
- Log Flood
- 正常 Message 饥饿

应使用：

有限 Retry
+
必要 Delay
+
DLQ

隔离失败。

---

# 5. DLQ

达到 Retry 上限或明确不可恢复：

进入 DLQ。

DLQ 用于：

- Failure Isolation
- Troubleshooting
- Context Preservation
- Recovery

需要能够关联：

- messageId
- businessKey
- messageType
- retryCount
- failure reason

DLQ 不是：

“失败消息垃圾桶”。

---

# 6. DLQ Replay

Replay 必须假设：

原业务可能已经部分成功。

因此 Replay 仍需要正常：

- Idempotency
- State Check
- Unique Constraint

禁止：

DLQ Replay
→ 绕过业务状态
→ 强制执行。

---

# 7. Poison Message

持续导致消费失败的 Message：

最终必须被隔离。

不能让它无限占用整个 Queue。

应进入：

DLQ
+
日志 / 告警
+
人工分析或安全 Replay。

---

# 8. Retry Verification

根据实现验证：

- Temporary Failure
- Retry Count
- Retry 后成功
- Retry Exhausted
- Permanent Failure
- DLQ Routing
- DLQ Context
- Replay Idempotency

测试重点不是：

Consumer 最终没有抛异常。

而是：

失败消息进入了正确、可恢复、不会无限循环的路径。
# EventHub Infrastructure Review

仅当修改涉及：

- MySQL
- Flyway
- SQL / Index
- Redis
- Lua
- RabbitMQ
- Outbox
- Thread Pool
- Performance

时读取。

---

# 1. Database

检查 Schema 变化是否：

- 通过新 Flyway Migration
- 同步 Java Mapping
- 保留必要 Unique Constraint
- 有合理 Index

不要只修改 Entity。

---

# 2. Core Database Safety Lines

重点检查：

`event_inventory`
→ UNIQUE(session_id)

`ticket_order`
→ UNIQUE(order_no)
→ UNIQUE(reservation_id)

`ticket`
→ UNIQUE(ticket_code)
→ UNIQUE(order_id)
→ UNIQUE(user_id, session_id)

`waiting_list`
→ UNIQUE(user_id, session_id)

Review 不应无意删除这些安全线。

---

# 3. SQL

核心并发 SQL 检查：

- Conditional UPDATE
- affectedRows
- State Condition

查询 SQL 检查：

- N+1
- Unbounded Query
- Pagination
- Index Alignment

没有真实证据：

不要随意给出复杂性能优化。

---

# 4. Redis

检查：

- Key 是否统一管理
- TTL 是否符合数据语义
- Lua 是否保持合理原子边界
- Core Lua 是否资源文件
- occupied / purchased 是否混淆
- FLASH Failure 是否 Fail Fast

警惕：

Redis Down
→ FLASH Direct MySQL。

---

# 5. RabbitMQ

检查：

- Message Envelope
- Business Key
- Outbox Transaction
- Confirm / Return
- Manual ACK
- Consumer Idempotency
- Bounded Retry
- DLQ

确认代码没有错误宣称：

Exactly Once。

---

# 6. Consumer Crash Window

特别检查：

DB COMMIT
→ ACK 前 Crash

消息重新投递后：

是否仍然安全。

Order Create：

reservationId + UNIQUE(reservation_id)

应继续兜底。

---

# 7. Outbox

检查：

Business State
+
Outbox Record

是否同一 Local Transaction。

检查 Publisher 是否错误依赖：

“每条消息只发一次”。

Duplicate Publish：

必须被允许。

---

# 8. Timeout MQ

Timeout Message：

只能触发状态检查。

不能：

收到消息
→ 无条件 status = TIMEOUT。

仍应要求：

WAIT_PAY

以及必要 expireTime 条件。

---

# 9. Thread Pool

自定义 Thread Pool 只在确有价值的场景使用。

检查：

- Core Size
- Max Size
- Bounded Queue
- Rejection Policy
- Thread Name
- Error Handling
- Shutdown

RabbitMQ Listener：

不要再套无意义第二层 ThreadPool。

---

# 10. Performance

警惕没有证据的：

- Cache Introduction
- Consumer Concurrency Increase
- Huge Thread Pool
- Index Explosion
- Complex Lua
- Early Optimization

性能改变应尽量由：

Benchmark
Metrics
EXPLAIN
Profile

支持。
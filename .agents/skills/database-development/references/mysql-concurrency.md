# EventHub MySQL 并发与幂等参考规则

仅当任务涉及：

- Conditional UPDATE
- affectedRows
- Inventory Competition
- Order State Competition
- Transaction
- Idempotency
- Duplicate Key
- SELECT FOR UPDATE

时读取。

---

# 1. NORMAL 库存竞争

NORMAL 抢票使用 MySQL Conditional UPDATE。

核心语义：

只有：

`available_stock > 0`

才能：

`available_stock - 1`

同时：

`locked_stock + 1`

成功与否通过：

`affectedRows`

判断。

禁止：

SELECT stock
→ Java 判断
→ UPDATE stock

这种存在并发窗口的实现。

未经新的架构决策，不增加：

- synchronized
- JVM Lock
- Redis Lock
- Redisson Lock
- SELECT FOR UPDATE

作为 NORMAL 默认库存竞争方案。

---

# 2. Order 状态竞争

涉及：

- Pay
- Cancel
- Timeout
- Refund

等并发状态转换时，优先采用：

Expected State
→ Conditional UPDATE
→ Target State

例如：

`WHERE status = WAIT_PAY`

然后通过：

`affectedRows`

判断当前请求是否真正获得状态转换资格。

避免：

SELECT status
→ Java 判断
→ 无条件 UPDATE

SQL 条件必须与 Order 状态机保持一致。

---

# 3. Database Idempotency

重要业务优先形成多层安全：

Business State
+
Conditional UPDATE
+
Unique Constraint

例如 FLASH Order：

Consumer Idempotency
+
`UNIQUE(reservation_id)`

共同保证一个 Reservation 不产生多个 Order。

应用层幂等不能取代数据库 Constraint。

---

# 4. Duplicate Key

遇到 Duplicate Key 时：

必须结合具体 Constraint 和业务语义判断。

例如：

重复消费导致相同 reservationId 已存在 Order：

可能属于正常幂等结果。

但其他 Unique Constraint 冲突：

可能代表真实数据错误。

禁止：

catch DuplicateKeyException
→ 全部吞掉

也禁止：

所有 Duplicate Key
→ 无限 Retry。

---

# 5. SELECT FOR UPDATE

不要默认使用：

`SELECT ... FOR UPDATE`

解决所有数据库并发。

先判断能否通过：

- Conditional UPDATE
- Unique Constraint
- 状态字段
- Atomic SQL

解决。

只有确实需要：

读取当前数据
+
根据读取结果执行多个必须串行化操作

且 Conditional UPDATE 无法合理表达时，
才考虑悲观锁。

使用时必须明确：

- 锁哪条记录
- Transaction Boundary
- Lock Duration
- Lock Range
- Deadlock Risk
- Hot Session Throughput

---

# 6. Transaction

多个 MySQL 修改如果必须共同成功或共同失败：

使用合理的 Spring Local Transaction。

事务应保持：

短
+
明确。

避免在持有数据库事务期间执行：

- Remote Call
- 长耗时操作
- 不必要等待

跨 Maven Module 不等于分布式事务。

只要处于：

同 JVM
+
同 DataSource
+
同 TransactionManager

跨模块同步数据库操作仍可参与同一 Local Transaction。

---

# 7. 验证

并发相关 SQL 至少根据范围验证：

- affectedRows
- 库存不小于 0
- 状态转换只能成功一次
- Duplicate Request
- Unique Constraint
- Transaction Rollback
- 真实并发竞争

核心数据库并发行为优先使用真实 MySQL 验证。
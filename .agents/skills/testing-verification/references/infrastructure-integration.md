# EventHub Infrastructure Integration Testing

仅当任务涉及：

- MySQL
- Flyway
- Redis
- Lua
- RabbitMQ
- Docker Compose
- Integration Test Data
- Test Fixtures

时读取。

---

# 1. V1 使用真实基础设施

EventHub V1 集成测试使用：

`docker-compose.yml`

提供：

- MySQL
- Redis
- RabbitMQ

V1 暂不引入：

Testcontainers。

如果未来出现：

- CI Isolation Problem
- Port Conflict
- Environment Reproducibility Problem
- Lifecycle Management Problem

再评估 Testcontainers。

---

# 2. MySQL 不以 H2 作为核心替代

以下行为使用真实 MySQL 验证：

- Conditional UPDATE
- affectedRows
- Unique Constraint
- Transaction
- InnoDB Concurrency
- MySQL SQL
- Index
- EXPLAIN

H2 Green：

不能作为 MySQL 行为正确的最终证明。

---

# 3. Flyway

涉及 Schema 修改时验证：

- Migration 实际执行成功
- Schema 可以通过 Migration 构建
- Column 正确
- Constraint 正确
- Index 正确
- Java Mapping 一致

不要只检查 SQL 文件文本。

---

# 4. Database Constraint

根据任务真实验证：

`event_inventory`

同一 sessionId 唯一。

`ticket_order`

当前 `order_no` 以及 `(user_id, session_id, purchase_guard)` 唯一。未来 FLASH
Reservation 精确字段与唯一安全线尚未冻结。

`ticket`

userId + sessionId 唯一。

`waiting_list`

WaitingList capability 为 Future Planned，Schema 与唯一约束尚未冻结。

数据库安全线必须通过数据库行为证明。

---

# 5. Redis

以下行为使用真实 Redis：

- Lua
- TTL
- Atomicity
- Set / ZSet
- Rate Limit
- Reservation State
- occupied / purchased

Mock RedisTemplate：

不能证明这些行为正确。

---

# 6. Lua

核心 Lua 必须真实执行。

根据脚本范围验证：

- KEYS
- ARGV
- Return Protocol
- State Mutation
- TTL
- Duplicate Request
- Atomic Result

Java 和 Lua Contract 必须一致。

---

# 7. RabbitMQ

可靠性关键路径使用真实 RabbitMQ 验证。

根据任务检查：

- Publish
- Routing
- Consumer
- Duplicate Delivery
- Retry
- DLQ
- Timeout Message
- Consumer Idempotency

只验证：

`rabbitTemplate.send()` 被调用

不能证明 MQ 可靠性。

---

# 8. Test Data

Integration Test 必须有明确：

- Setup
- Fixture
- Cleanup

策略。

禁止依赖：

- Developer Local Data
- Previous Test
- Test Execution Order

测试应能够：

单独执行
和
整体执行

得到一致结果。

---

# 9. Test-only Support

允许在：

`src/test`

建立：

- TestDataFactory
- TestFixtures
- TestDatabaseCleaner
- TestRedisCleaner
- MessageTestFactory

禁止为了测试创建生产接口：

`/deleteEverything`

或类似危险能力。

---

# 10. 测试数据清理

测试环境允许受控：

DELETE / Cleanup。

必须明确只作用于：

Test Environment。

破坏性清理能力需要通过：

- Profile
- Environment
- Test Configuration

严格限制。

不得存在误清 Production 的路径。

---

# 11. Infrastructure Failure

如果 Docker / MySQL / Redis / RabbitMQ 不可用：

不要假装相关 Integration Test 已经验证。

必须明确说明：

- 哪个依赖不可用
- 哪些测试没执行
- 哪些结论暂时不能得到

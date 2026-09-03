# EventHub Inventory 模块开发规则

> 版本：V1.0
>
> 本文件适用于 `eventhub-inventory`。
> 本文件补充仓库根目录 `AGENTS.md`。
> 未特别规定的事项继续遵守仓库级规则。
>
> Inventory 是 EventHub 的核心并发模块。
> 修改库存相关代码时，业务正确性、并发正确性和幂等性优先于代码简洁性和性能优化。

---

# 1. 模块职责

`eventhub-inventory` 负责：

- Session 库存
- 库存初始化、锁定和释放
- Redis FLASH 库存
- 抢票相关 Redis Reservation 状态
- Redis Lua
- WaitingList
- 抢票相关限流

本模块不负责：

- TicketOrder 创建和订单状态机
- 支付与退款业务
- Ticket 创建
- HTTP Controller
- 跨领域业务流程编排

跨领域流程由 `eventhub-app` 中的 ApplicationService 编排。

---

# 2. 库存业务粒度

所有抢票和库存操作必须以：

`sessionId`

作为业务粒度。

禁止使用 `eventId` 直接进行：

- 库存扣减或释放
- Redis 抢票库存操作
- WaitingList 排队
- Session 抢票限流

Event 必须通过 EventSession 对应具体库存。

---

# 3. 库存核心不变量

库存始终必须满足：

`totalStock = availableStock + lockedStock + soldStock`

并且：

`totalStock >= 0`

`availableStock >= 0`

`lockedStock >= 0`

`soldStock >= 0`

允许的核心库存转换为：

抢票成功：

`availableStock - 1`
`lockedStock + 1`

支付成功：

`lockedStock - 1`
`soldStock + 1`

取消或超时：

`lockedStock - 1`
`availableStock + 1`

退款：

`soldStock - 1`
`availableStock + 1`

任何实现都不得破坏库存总量不变量。

不得通过单独修改某一个库存字段绕过完整业务状态转换。

---

# 4. NORMAL 抢票规则

NORMAL 抢票使用：

MySQL 条件 UPDATE
+
affectedRows

只有：

`available_stock > 0`

时才能完成：

`available - 1`
`locked + 1`

MySQL 条件 UPDATE 负责 NORMAL 模式下的原子库存竞争控制。

未经新的架构决策和用户确认，不要自行增加：

- `synchronized`
- JVM Lock
- Redis Distributed Lock
- Redisson Lock
- `SELECT ... FOR UPDATE`

不要为了“更安全”破坏已经确定的架构演进路线。

---

# 5. FLASH 抢票规则

FLASH 模式使用 Redis 作为高并发库存执行层。

抢票资格判断和对应 Redis 状态变化必须通过 Lua 保持必要的原子性。

禁止实现：

GET
→ Java 判断
→ DECR

也禁止把一个必须原子完成的抢票操作拆成：

Redis Command
→ Java 判断
→ Redis Command

FLASH 抢票原子过程需要保持以下状态的一致变化：

- 历史购买资格检查
- 当前 Reservation 检查
- 售票条件检查
- 库存检查
- 库存扣减
- occupied-users 更新
- reservationId 记录
- 临时 Reservation 状态建立

调整上述职责时，必须首先验证：

原子性、幂等性和失败路径是否仍然正确。

---

# 6. NORMAL 与 FLASH 不得混用

两种抢票模式必须保持明确边界：

NORMAL
→ MySQL 条件 UPDATE

FLASH
→ Redis Lua

不要为了代码复用强行将两套并发控制方案统一。

尤其禁止在 FLASH Redis 故障时直接把全部流量切换到 NORMAL MySQL 抢票流程。

---

# 7. MySQL 与 Redis

MySQL 是最终事实来源。

Redis 在本模块中承担：

- FLASH 高并发执行
- 抢票库存状态
- 临时 Reservation 状态
- WaitingList
- 限流

Redis 中的状态不得被视为永久最终事实。

Redis 与 MySQL 出现不一致时，通过：

幂等
+
补偿
+
对账

恢复最终正确状态。

不要通过万能分布式锁解决跨组件一致性。

---

# 8. Redis 故障策略

NORMAL：

Redis 故障时，可以根据既定设计有限降级到 MySQL。

FLASH：

Redis 故障时必须 Fail Fast。

禁止：

FLASH Redis Failure
→ 全量请求自动降级 MySQL

避免热点流量直接压垮数据库。

---

# 9. 库存释放必须幂等

同一个 Reservation 对应的库存只能被有效释放一次。

库存释放不能只是无条件：

`stock + 1`

必须结合：

- Reservation 身份
- 当前状态
- 是否已经释放
- 必要的原子状态变化

以下重复执行均不得造成库存重复恢复：

- Cancel
- Timeout
- Compensation
- MQ 重复消息
- Reconciliation

第二次处理相同释放请求时，应安全返回已有结果或 no-op，而不是再次增加库存。

---

# 10. WaitingList

同一个：

`userId + sessionId`

不得出现重复有效候补资格。

库存释放后：

队首符合条件的用户获得购买资格。

购买资格有效期：

10 分钟。

获得候补资格：

不等于自动创建订单。

禁止因为候补成功直接自动创建：

`WAIT_PAY`

订单。

资格过期后：

当前资格进入 `EXPIRED`

然后继续处理下一位符合条件的用户。

WaitingList 的顺序和资格变化必须可验证。

---

# 11. 限流

FLASH 请求顺序至少保持：

用户级限流
→ Session 级限流
→ Redis Lua 抢票

用户级维度：

`userId + sessionId`

Session 级维度：

`sessionId`

V1 使用：

Redis Lua Fixed Window

限流阈值必须配置化。

未经真实 Benchmark 验证，不得把某个阈值描述成系统最优配置。

---

# 12. 分布式锁原则

V1 不以 Redisson 或其他分布式锁作为核心库存方案。

遇到并发问题时，优先判断是否能够通过：

- MySQL 条件 UPDATE
- Redis Lua
- 数据库唯一约束
- 状态机
- 幂等
- 消息去重

解决。

不要默认使用分布式锁。

---

# 13. 模块边界

Inventory 对外能力只能通过：

`com.eventhub.inventory.api`

暴露。

外部模块不得直接访问 Inventory 的：

- mapper
- repository
- redis
- waiting
- ratelimit
- Service 实现类

Inventory 同样不得直接访问其他业务模块的 Mapper 或 Repository。

支付、取消、退款等跨领域流程由 `eventhub-app` 的 ApplicationService 编排。

例如支付流程应由：

PaymentApplicationService

分别调用：

Order API
→ `WAIT_PAY → PAID`

Inventory API
→ `locked → sold`

Order 模块不得直接操作 InventoryMapper。

---

# 14. 并发修改后的检查

修改以下逻辑时：

- 库存扣减
- 库存释放
- Redis Lua
- Reservation
- WaitingList
- 限流
- 补偿

必须主动验证：

是否可能超卖。

是否可能出现负库存。

库存总量不变量是否成立。

重复请求是否安全。

重复释放是否安全。

Redis 原子性是否仍然成立。

异常路径是否可能遗漏库存恢复。

并发请求同时执行时是否仍然正确。

不要只验证单线程 Happy Path。

---

# 15. 最低测试要求

涉及核心库存逻辑的修改，应根据实际修改范围覆盖：

- 库存为 0
- 库存为 1
- 正常库存扣减
- 并发库存扣减
- 重复 Reservation
- 重复释放
- Redis Lua 边界
- Redis 故障路径
- WaitingList 重复加入
- WaitingList 资格过期
- Rate Limit 边界

NORMAL 抢票至少需要证明：

并发请求数 > 总库存

最终：

成功数 <= 总库存

`availableStock >= 0`

并且：

`totalStock = availableStock + lockedStock + soldStock`

FLASH 完成后必须增加对应的并发和集成验证。

---

# 16. 文档维护

以下设计变化需要检查并更新对应文档：

- 库存模型
- NORMAL 抢票
- FLASH 抢票
- Redis Key
- Redis Lua
- WaitingList
- 限流
- Redis 故障策略
- 库存补偿

优先维护：

`docs/architecture/reservation-flow.md`

`docs/middleware/redis.md`

`docs/architecture/consistency.md`

本文件只维护 Inventory 长期稳定的行为约束。

Redis、Lua 和高并发原理教程不要写入本文件，应维护在 docs 或对应 Skill 中。
# EventHub FLASH Lua 与 Rate Limit

仅当任务涉及：

- FLASH Stock
- Redis Lua
- Atomic Reservation
- Rate Limit
- Distributed Lock

时读取。

---

# 1. FLASH Stock

FLASH 使用 Redis Stock 作为高并发执行状态。

禁止：

GET stock
→ Java 判断
→ DECR stock

库存判断和扣减必须通过：

Redis Lua

原子完成。

---

# 2. 一次 FLASH 请求的原子边界

典型 Lua 判断包括：

- Sales Time
- purchased
- Existing Reservation
- Stock

成功以后根据当前实现完成必要：

- stock - 1
- occupied
- reservation-map
- Reservation Temporary State

如果这些状态共同决定：

“用户是否成功获得当前抢票处理资格”

则必须位于同一个合理原子边界。

不要：

Lua A
→ Java
→ Redis Command
→ Lua B

然后假设整体仍然原子。

---

# 3. Lua 只实现最小原子单元

不要让 Lua 成为整个 Ticketing Backend。

Lua 不负责：

- 完整 Order
- Ticket Lifecycle
- Notification
- 复杂 Compensation
- 跨领域 ApplicationService

Lua 应保持：

- Input 明确
- Branch 有限
- 无界循环禁止
- Complexity 可控
- Return Protocol 清晰
- 易测试

---

# 4. Return Protocol

Lua 返回结果需要让 Java 明确认出业务结果。

例如：

- SUCCESS
- SOLD_OUT
- ALREADY_PURCHASED
- ALREADY_PROCESSING
- NOT_STARTED
- ENDED

具体编码按当前实现确定。

不要只返回：

0 / 1

然后让 Java 猜测失败原因。

Return Protocol 变化必须同步：

- Java Mapping
- Tests
- Docs

---

# 5. Lua Resource

核心脚本维护在：

`eventhub-inventory/src/main/resources/lua/`

例如：

`flash-reserve.lua`

`rate-limit.lua`

禁止大型 Java Inline Script。

KEYS 与 ARGV 的顺序属于 Java / Lua Contract，
修改一端必须检查另一端。

---

# 6. Rate Limit

FLASH 至少考虑：

## User Dimension

userId + sessionId

## Session Dimension

sessionId

V1 使用：

Redis Lua Fixed Window。

判断与计数更新必须原子。

限流 TTL 与 Window 保持一致。

Threshold：

- 配置化
- 根据真实 Benchmark 调整

不要凭感觉把某个数字永久硬编码。

---

# 7. Fixed Window 边界

Fixed Window 存在：

窗口边界流量突刺。

但 V1 不因此提前升级：

- Sliding Window
- Token Bucket
- Leaky Bucket

只有真实 Benchmark 证明当前方案不足时再演进。

---

# 8. Distributed Lock

V1 不把 Redisson Distributed Lock 作为核心并发方案。

优先：

- Lua
- Atomic Command
- Redis Data Structure
- SET NX 合理语义
- MySQL Conditional UPDATE
- Unique Constraint

只有必须跨多个无法原子化步骤形成互斥，
且现有机制无法合理解决时，
才重新评估锁方案。

---

# 9. FLASH Lua 验证

至少根据当前脚本覆盖：

- SUCCESS
- SOLD_OUT
- ALREADY_PURCHASED
- ALREADY_PROCESSING
- NOT_STARTED
- ENDED

并验证：

- Success 才扣库存
- Failure 不错误扣库存
- Duplicate Request 不重复扣库存
- stock >= 0
- occupied / reservation-map 与结果一致
- 高并发执行保持正确

核心 Lua 必须在真实 Redis 上执行验证。
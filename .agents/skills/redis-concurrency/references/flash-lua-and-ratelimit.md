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

# 2. Phase 10 当前 Redis 状态与原子边界

当前只冻结两个 same-slot Key：

- `eventhub:flash:{sessionId}:meta`
- `eventhub:flash:{sessionId}:admissions`

`meta` Hash 字段为：

- `state = PREPARED`
- `capacity`
- `stock`
- `saleStartUs`
- `saleEndUs`

`admissions` Hash 以 `userId` 为 field，value 只允许：

- `P:<admissionId>`
- `A:<orderId>`
- `R:<orderId>`

当前稳定不变量是：

`stock + count(P) + count(A) = capacity`

`R` 不占用 admission stock，但继续阻止同一用户再次 admission。售票窗口、重复
准入、stock 与 P/A/R mutation 必须位于对应 Lua 原子边界。

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

当前精确协议为：

- Prepare：`PREPARED`、`IDEMPOTENT_PREPARED`、`WINDOW_CLOSED`、`STATE_CONFLICT`
- Admission：`ADMITTED`、`IDEMPOTENT_PENDING`、`ALREADY_PENDING`、
  `ALREADY_ADMITTED`、`SOLD_OUT`、`NOT_STARTED`、`ENDED`、`NOT_PREPARED`、
  `STATE_CONFLICT`
- Settle：`ACTIVATED`、`IDEMPOTENT_ACTIVATED`、`RELEASED`、
  `IDEMPOTENT_RELEASED`、`FENCE_CONFLICT`、`NOT_PREPARED`、`STATE_CONFLICT`
- Lifecycle：`RELEASED`、`IDEMPOTENT_RELEASED`、`REFUNDED`、
  `IDEMPOTENT_REFUNDED`、`FENCE_CONFLICT`、`NOT_PREPARED`、`STATE_CONFLICT`

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

Phase 10 当前精确为四个脚本：

- `flash_prepare.lua`
- `flash_reserve.lua`
- `flash_reservation_settle.lua`
- `flash_order_lifecycle.lua`

当前没有第五个业务 Lua；`rate-limit.lua` 仍是 Future Proposal。

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

Future Proposal：

Redis Lua Fixed Window Rate Limit。

Current Phase 10：`NOT CURRENT REQUIREMENT`，不属于 Phase 10 completion criteria。

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

至少根据当前四个脚本覆盖：

- Prepare 首次成功、等价幂等、窗口关闭、状态冲突
- Admission 成功、同 admission ID 幂等、不同 pending、已有 A/R、售罄、窗口边界
- Settle `P → A`、匹配 P release、幂等与 fencing
- Lifecycle 匹配 A release、`A → R`、重复 refund、P/mismatch fencing

并验证：

- Success 才扣库存
- Failure 不错误扣库存
- Duplicate Request 不重复扣库存
- stock >= 0
- `stock + count(P) + count(A) = capacity`
- P/A/R 与 expected admission/order identity 一致
- 高并发执行保持正确
- MySQL commit 后 Redis lifecycle failure 不反转数据库结果

核心 Lua 必须在真实 Redis 上执行验证。

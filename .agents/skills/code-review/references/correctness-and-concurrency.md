# EventHub Correctness 与 Concurrency Review

仅当修改涉及：

- Inventory
- Reservation
- Order
- Payment
- Cancel
- Timeout
- Refund
- WaitingList
- Transaction
- Idempotency
- Compensation
- Concurrency

时读取。

---

# 1. Inventory Invariant

检查是否始终满足：

totalStock
=
availableStock
+
lockedStock
+
soldStock

以及：

availableStock >= 0

lockedStock >= 0

soldStock >= 0。

任何状态变化都必须保持库存守恒。

---

# 2. NORMAL Inventory

检查是否保持：

Conditional UPDATE

例如要求：

available_stock > 0

再进行库存变化。

警惕：

SELECT stock
→ Java Check
→ UPDATE

这种并发窗口。

未经架构变化：

不要自动加入：

- synchronized
- Redis Lock
- SELECT FOR UPDATE

代替原设计。

---

# 3. FLASH

检查是否出现：

GET
→ Java判断
→ DECR

等非原子流程。

决定抢票处理资格的关键 Redis 操作：

应由合理 Lua 原子完成。

检查重复请求：

是否会重复扣库存。

---

# 4. Order State Machine

当前持久状态转换：

WAIT_PAY → PAID

WAIT_PAY → CANCELLED

WAIT_PAY → CLOSED（Timeout 是 operation / trigger，`CLOSED` 是持久状态）

当前转换：PAID → REFUNDED（`CURRENT / FROZEN / IMPLEMENTED`）

检查是否出现：

无条件 setStatus

或：

非法状态跳转。

---

# 5. State Race

重点检查：

Pay vs Cancel

Pay vs Timeout

Cancel vs Timeout

是否使用：

Expected State
+
Conditional Update
+
affectedRows

保证只有一个竞争操作真正成功。

---

# 6. Idempotency

对以下能力检查重复调用：

- Pay
- Cancel
- Timeout
- Refund
- Reservation
- MQ Consumer
- Compensation

重复调用：

不得产生第二次有效业务副作用。

---

# 7. Purchase Eligibility

本节退款规则属于 `CURRENT / FROZEN / IMPLEMENTED`。

检查：

Refund

是否只恢复库存。

历史：

purchased

资格不能因为 Refund 被删除。

用户成功购买并获得 Ticket 后：

即使 REFUNDED

也不能再次参加同 Session 抢票。

---

# 8. Transaction

对于同 MySQL、同步跨模块操作：

如果业务要求共同成功 / 失败，

检查已冻结的事务所有者是否建立了正确 Local Transaction。

Phase 6 NORMAL 的明确例外由 `eventhub-order` 拥有事务，并按
`Order → Inventory` 调用 Public API；Phase 7 Pay-to-Ticket 与 Phase 9 Refund
是同一生命周期的窄扩展。Refund 由 Order 以 `READ_COMMITTED` 按
`Order → Ticket → Inventory` 编排，Ticket / Inventory 以 `MANDATORY` 参与；
通用默认仍是 App ApplicationService，这些例外不构成通用先例。

例如 NORMAL：

Inventory Lock
+
WAIT_PAY Order

不能留下 Partial Success。

---

# 9. Cross-component Transaction Illusion

警惕：

`@Transactional`

外围包含：

Redis
+
RabbitMQ
+
MySQL

然后代码假设三者共同回滚。

MySQL Local Transaction：

不能自动覆盖 Redis / RabbitMQ。

---

# 10. Compensation

检查补偿是否：

Idempotent。

同一 Reservation 可能被：

- Retry
- Timeout
- MQ Duplicate
- Reconciliation

重复触发恢复。

不能：

重复增加库存。

---

# 11. WaitingList

WaitingList capability 为 `FUTURE / PLANNED / NOT_IMPLEMENTED`。只有实现任务
冻结具体 Contract 后，才检查唯一约束、FIFO / Score、资格时长和推进规则；
不得把这些细节当成当前或已冻结规则。

---

# 12. Failure Path

检查：

Happy Path 之外是否可能产生：

- Stock Changed but Order Missing
- Order Changed but Inventory Wrong
- Reservation Stuck PROCESSING
- Duplicate Ticket
- Duplicate Stock Restore

核心流程必须能够处理部分失败和重复恢复。

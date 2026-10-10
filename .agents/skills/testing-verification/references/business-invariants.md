# EventHub Business Invariant Verification

仅当任务涉及核心业务流程时读取。

包括：

- NORMAL
- FLASH
- Payment
- Cancel
- Timeout
- Refund
- WaitingList
- Rate Limit
- Outbox

---

# 1. NORMAL Reservation

至少根据修改范围证明：

## Success

available
→ locked

同时：

创建 WAIT_PAY Order。

## Sold Out

Conditional UPDATE：

affectedRows = 0

不得创建有效订单。

## Transaction Rollback

如果：

Inventory UPDATE 成功
但
Order Create 失败

同步 MySQL Use Case 必须根据事务设计回滚。

不得留下：

Inventory Locked
+
Order Missing。

## Concurrency

有限库存面对更多并发请求：

不得 Oversell。

---

# 2. Inventory Invariant

根据测试范围验证：

`totalStock = availableStock + lockedStock + soldStock`

并且：

availableStock >= 0

lockedStock >= 0

soldStock >= 0。

高并发测试结束后也必须满足。

---

# 3. Payment

验证：

WAIT_PAY
→ PAID

Inventory：

locked
→ sold

还要覆盖：

- Duplicate Pay
- CANCELLED 后 Pay
- CLOSED 后 Pay
- Pay vs Cancel
- Pay vs Timeout

多个竞争状态：

最多一个产生有效业务结果。

---

# 4. Cancel

验证：

WAIT_PAY
→ CANCELLED

Inventory：

locked
→ available。

Duplicate Cancel：

不得重复恢复库存。

---

# 5. Timeout

验证：

WAIT_PAY
→ CLOSED

并释放：

locked
→ available。

如果当前 Order 已：

PAID
CANCELLED
CLOSED

重复 Timeout Trigger：

不得产生新的副作用。

---

# 6. Refund

本节为 `CURRENT / FROZEN / IMPLEMENTED` 的业务性质验证清单。

验证：

PAID
→ REFUNDED

Inventory：

sold
→ available。

Ticket：

VALID
→ REFUNDED。

USED Ticket 不可退款；Refund 与 Check-in 的条件更新最多一个获胜。

但：

Historical purchased eligibility

不得恢复。

退款后的用户：

仍不得重新参加同一 Session 抢票。

Duplicate Refund：

必须幂等成功，不得重复变更 Ticket 或恢复库存。

---

# 7. FLASH

本节为 Phase 10 `CURRENT / IMPLEMENTED / VERIFIED / FROZEN` 的同步 FLASH
Reservation 验证清单。

FLASH 验证不能只停在 Lua。

至少证明：

- ADMIN Preparation 从权威 MySQL Session/Inventory 建立 Redis `meta`；不合格或
  dirty state 不留下部分 Redis 状态
- 只有 Redis `ADMITTED` 才进入 `ReservationApi.reserveFlash(...)`
- 成功路径为 `P:<admissionId> → WAIT_PAY commit → A:<orderId>`
- 确定性 MySQL/Domain rejection 会匹配释放 P；补偿失败不伪装成功
- ambiguous database outcome 保留 P 且不恢复 stock；不得把模拟 evidence 扩张为
  真实网络 protocol-level ambiguity proof
- activation failure 不反转已提交的 WAIT_PAY / Inventory locked 事实
- Cancel / Timeout 在数据库提交后释放匹配 A；Refund 把匹配 A 转为 R；Pay 不修改 Redis
- lifecycle Redis failure 不反转已提交 MySQL，重复调用保持幂等与 fencing
- NORMAL Reservation 在 Redis unavailable 时保持独立

---

# 8. FLASH Lua

至少根据当前四个脚本覆盖：

- Prepare：`PREPARED` / `IDEMPOTENT_PREPARED` / `WINDOW_CLOSED` / `STATE_CONFLICT`
- Admission：`ADMITTED`、pending 幂等/冲突、已有 A/R、`SOLD_OUT`、窗口边界与未准备
- Settle：`P → A`、匹配 P release、幂等与 fencing
- Lifecycle：匹配 A release、`A → R`、重复 Refund、P/mismatch fencing

并检查：

- Failure 不错误扣库存
- Duplicate 不重复扣库存
- stock >= 0
- `stock + count(P) + count(A) = capacity`
- P/A/R identity 与 Redis stock mutation 一致
- real Redis concurrency 不产生负 stock 或重复 admission
- post-commit Redis failure 不反转 MySQL final fact

---

# 9. RabbitMQ Order Create

本节为 `FUTURE / FROZEN / NOT_IMPLEMENTED`；Phase 10 当前同步 FLASH 不创建
RabbitMQ business topology。

主动重复发送相同业务请求。精确业务 Key / `reservation_id` 尚未冻结。

最终：

只能存在一个合法 Order。

不要只检查 Consumer Method 调用次数。

---

# 10. Outbox

本节适用于未来 FLASH Outbox 实现；方向已冻结，精确 Schema / 状态机尚未冻结。

根据实现验证：

Business Data
+
Outbox

位于同一 Local Transaction。

Business Transaction Rollback：

不得只留下 Outbox。

Transaction Success：

必须留下可恢复的 Message Record。

Publisher Duplicate：

不得破坏最终业务结果。

---

# 11. WaitingList

WaitingList capability 为 `FUTURE / PLANNED / NOT_IMPLEMENTED`。具体实现冻结后
再按真实 Contract 验证 Join、排序、Qualification、过期与推进；当前不把
Schema、ZSet、资格时长或唯一约束列为硬性断言。

---

# 12. Rate Limit

Fixed Window Rate Limit 为 `FUTURE / PLANNED / NOT_IMPLEMENTED`，不是 Phase 10
correctness requirement。仅在后续实现后执行以下验证：

验证：

User Dimension：

userId + sessionId

Session Dimension：

sessionId

以及：

- Below Threshold
- Threshold Reached
- Reject
- Window Reset
- TTL
- Concurrent Count

Rate Limit 不能只用顺序调用验证原子性。

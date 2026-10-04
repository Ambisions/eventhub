# EventHub Redis Reservation 与 WaitingList

仅当任务涉及：

- occupied-users
- purchased-users
- reservation-map
- Reservation State
- WaitingList
- Qualification

时读取。

状态边界：Reservation business concept 为 `FUTURE / FROZEN / NOT_IMPLEMENTED`；
精确持久化模型、字段、`reservation_id` 与幂等 Key 尚未冻结。WaitingList
capability 为 `FUTURE / PLANNED / NOT_IMPLEMENTED`；Schema、Redis ZSet、
资格时长、唯一约束与 MQ flow 尚未冻结。以下具体数据结构均是设计候选，
不是 Current/Frozen Contract。

---

# 1. occupied-users

occupied 表示：

用户暂时占用了当前 Session 的抢票处理资格 / 库存位置。

occupied：

不等于最终购买成功。

Reservation 失败、取消或补偿时：

根据真实业务释放 occupied。

释放必须幂等。

重复 Compensation 不得：

重复增加库存
或
破坏合法状态。

---

# 2. purchased-users

purchased 表示：

历史成功购买事实。

用户一旦：

PAID
+
获得该 Session Ticket

即认为已经成功购买过。

当前即使：

PAID → REFUNDED

库存可以恢复，

但 purchased 资格不能自动删除。

必须区分：

Inventory Availability

和：

Historical Purchase Eligibility。

---

# 3. reservation-map

FLASH 可以在实现阶段评估：

userId + sessionId
→ stable reservation business key

识别当前用户是否已经存在抢票请求。

Lua 应优先检查 Existing Reservation。

重复请求应尽量返回：

Existing reservation result

而不是重新扣库存并创建第二套流程。

TTL 必须覆盖：

Reservation 仍可能处理或被查询的生命周期。

---

# 4. Reservation Redis State

Redis 可以维护快速状态：

- PROCESSING
- SUCCESS
- FAILED

但是 Redis 不是最终业务事实。

Redis Key 缺失或过期：

不等于：

该 Reservation 从未发生。

必要时应根据：

- Persistent Reservation
- Order
- MySQL

返回或恢复合理状态。

---

# 5. WaitingList

WaitingList 未来可以评估：

Redis ZSet。

Score 必须具有明确 FIFO / 排序语义。

例如：

joinedAt
或其他稳定顺序值。

Redis 用于高效排队和调度。

具体 MySQL 唯一安全线在 WaitingList Schema 冻结后确定。

---

# 6. Qualification

库存释放后：

队首用户获得限时购买资格。

精确资格有效期尚未冻结。

获得资格：

不等于：

自动创建 WAIT_PAY Order。

资格过期后：

应继续允许下一候补用户获得机会。

Qualification TTL 必须严格对应业务时长。

禁止通过 Cache TTL Jitter 改变真实资格时间。

---

# 7. 验证

根据任务至少检查：

## Reservation

- Duplicate Request
- Existing reservation result
- PROCESSING
- SUCCESS
- FAILED
- Key Missing / Expired

## purchased / occupied

- occupied 正确建立
- Failure 正确释放
- purchased 不被 Refund 删除
- Duplicate Release 幂等

## WaitingList

- 按实现阶段冻结的 Contract 验证 Duplicate Join、排序、Qualification、过期与推进

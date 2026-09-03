# EventHub Index 与 SQL Performance 参考规则

仅当任务涉及：

- Index
- Query Optimization
- EXPLAIN
- Slow SQL
- Pagination
- N+1
- Database Performance

时读取。

---

# 1. Index 从查询路径出发

设计 Index 前先明确：

- WHERE
- JOIN
- ORDER BY
- GROUP BY
- Query Frequency
- Data Volume
- Selectivity

不要先创建大量 Index 再寻找用途。

---

# 2. 当前重要查询索引

需要修改相关查询时重新检查：

`ticket_order(user_id, created_at)`

用于用户订单查询。

`ticket_order(session_id, status)`

用于 Session 状态查询。

`ticket_order(status, expire_time)`

用于订单超时扫描。

`waiting_list(session_id, status, joined_at)`

用于候补顺序查询。

`waiting_list(status, qualification_expire_time)`

用于资格过期扫描。

这些是当前设计，不代表未来永远不能调整。

调整必须来自真实查询变化。

---

# 3. 联合索引

设计联合 Index 时考虑：

- 最左前缀
- 等值条件
- 范围条件
- ORDER BY
- 字段选择性
- Covering Index
- Write Cost

不要因为 SQL 有多个字段就机械创建多个单列 Index。

也不要为了 Covering Index 无限增加字段。

Index 会增加：

INSERT
UPDATE
DELETE

成本。

---

# 4. EXPLAIN

以下 SQL 根据需要执行 EXPLAIN：

- 高频查询
- Core SQL
- JOIN
- ORDER BY
- Range Query
- 新增复杂查询
- 新增 / 修改 Index 后
- 已知性能问题 SQL

重点关注：

- type
- key
- rows
- Extra

创建了 Index：

不等于：

Optimizer 一定会使用。

执行计划与预期不同：

先分析原因，
再修改 SQL 或 Index。

---

# 5. N+1

检查列表业务是否存在：

for each record
→ SELECT

如果可以批量查询：

不要在 Java Loop 中重复访问数据库。

---

# 6. 无界查询

会持续增长的数据默认考虑：

Pagination。

例如：

- User Orders
- Event List
- WaitingList Records

不要一次读取无界数据集。

同时避免读取大量当前业务不需要的字段。

---

# 7. 性能优化必须有证据

优先依据：

- EXPLAIN
- Slow Query
- JMeter
- QPS
- P95 / P99
- Connection Pool
- CPU / IO

优化流程：

发现瓶颈
→ 定位原因
→ 提出假设
→ 修改
→ 再验证

不要仅凭：

“这个 SQL 看起来可能慢”

就引入复杂：

- Cache
- Denormalization
- Sharding
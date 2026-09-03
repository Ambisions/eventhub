# EventHub Redis Failure、Compensation 与 Operations

仅当任务涉及：

- Redis Failure
- Timeout
- Retry
- Compensation
- Reconciliation
- KEYS / SCAN
- Big Key
- Hot Key
- Redis Performance

时读取。

---

# 1. NORMAL 与 FLASH 故障策略不同

## NORMAL

核心库存竞争在 MySQL。

Redis Cache / Auxiliary Capability 故障时：

可以按照既定方案有限降级到 MySQL。

必须考虑数据库承载能力。

## FLASH

Redis 是保护 MySQL 的高并发入口。

Redis 不可用：

默认 Fail Fast。

禁止：

Redis Down
→ FLASH 全量直打 MySQL。

这会把 Redis Failure 扩散成 MySQL Failure。

---

# 2. Redis Timeout 与异常

需要区分：

- Timeout
- Connection Failure
- Missing Key
- Lua Error
- Infrastructure Failure

不要为所有 Redis 操作统一：

catch
→ retry 3 times。

需要根据业务语义决定：

- Retry
- Fail Fast
- Fallback
- Async Compensation

高并发同步入口不要无限 Retry，
否则 Retry 本身可能成为流量放大器。

---

# 3. Compensation 必须幂等

同一个 Reservation 的同一种释放行为：

最多产生一次有效业务结果。

必须考虑同时来自：

- Consumer Retry
- MQ Duplicate
- Timeout
- Cancellation
- Compensation
- Reconciliation

的重复触发。

重复补偿不得：

- Duplicate stock restore
- 错误删除 purchased
- 破坏已有正确状态

---

# 4. Reconciliation

Redis 与 MySQL 允许存在短暂差异。

Reconciliation 可以根据实现检查：

- Redis Stock 与 MySQL 异常偏差
- Long PROCESSING Reservation
- occupied 未正确释放
- 其他可恢复状态

但对账必须识别：

合法处理中业务。

禁止简单：

Redis ← MySQL Full Overwrite

否则可能破坏正在执行的 FLASH 请求。

---

# 5. 禁止主链路 KEYS

高并发请求路径不得使用：

`KEYS pattern`

扫描 Key Space。

需要遍历时根据场景使用：

- Known Keys
- 明确的数据结构
- SCAN
- MySQL Query
- 专门索引结构

FLASH Hot Path 不得依赖 Key Space Scan。

---

# 6. Big Key 与无界数据结构

使用：

- Set
- ZSet
- Hash
- List

时必须考虑：

- Scope
- Session Isolation
- Max Size
- TTL
- Cleanup

重点关注：

- purchased-users
- occupied-users
- waiting ZSet
- Reservation Keys
- Rate Limit Keys

不要建立一个持续无限增长的全平台 Global Set。

---

# 7. Performance

性能分析优先依据：

- Redis Latency
- QPS
- Lua Execution Time
- Network Round Trip
- Hot Key
- Big Key
- Redis CPU
- Lettuce State
- JMeter P95 / P99

FLASH 原子逻辑可以通过 Lua 降低多次网络 Round Trip。

但是：

减少 Round Trip

不等于：

把所有业务都塞进一个巨大 Lua。

---

# 8. Failure Verification

根据任务范围验证：

- Redis unavailable
- Redis timeout
- Lua error
- Duplicate Compensation
- Reconciliation

特别确认：

FLASH Failure

不会无保护地降级到 MySQL。

性能修改需要真实 Benchmark 才能形成性能结论。
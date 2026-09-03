# EventHub Redis Cache、Key 与 TTL

仅当任务涉及：

- Cache
- Key Design
- TTL
- Cache Aside
- Cache Penetration
- Cache Breakdown
- Cache Avalanche
- Serialization

时读取。

---

# 1. Key Design

Key 必须表达：

- Domain
- Resource
- Identifier
- Purpose

避免：

`stock:1`

这类上下文不足的 Key。

Key 应统一通过：

Key Builder / Constants

维护。

修改 Key Format 时同步检查：

- Java
- Lua
- Tests
- Compensation
- Existing Key Compatibility

EventHub V1 不提前围绕 Redis Cluster Hash Slot 复杂化 Key。

---

# 2. TTL 来自数据语义

不要所有 Key 都使用统一 TTL。

TTL 根据数据生命周期确定。

普通缓存：

根据缓存新鲜度设计。

Reservation：

覆盖实际异步处理和查询周期。

Rate Limit：

与限流窗口一致。

Waiting Qualification：

严格对应真实资格有效期。

必须区分：

Cache TTL

与：

Business TTL。

Cache TTL 可以根据缓存策略调整。

Business TTL 不得为了缓存优化改变业务有效期。

业务型 TTL 优先配置化。

---

# 3. Cache Aside

Event / Session 等普通读取缓存优先采用：

Cache Aside。

Read：

Cache
→ Miss
→ MySQL
→ Cache

Write：

MySQL 正式更新成功
→ Cache Invalidate / Refresh

不要把：

MySQL Update
+
Redis Update

认为是一个天然原子事务。

缓存失效失败：

依靠 Retry、TTL 或既定补偿最终恢复。

---

# 4. Cache Penetration

面对大量不存在 ID 查询时可以根据真实风险考虑：

- 参数合法性校验
- Short TTL Null Cache

不要默认引入 Bloom Filter。

空值缓存需要：

- 与正常数据区分
- TTL 较短
- 避免新建数据长期不可见

---

# 5. Cache Breakdown

Hot Event / Session Cache 失效时，
根据真实访问模式判断是否存在击穿。

可以考虑：

- 合理 TTL
- Cache Warmup
- TTL Jitter
- 必要的热点重建保护

不要默认使用 Distributed Lock。

如果确实采用互斥重建，
必须明确：

- Lock Granularity
- Timeout
- Failure Behavior
- 是否值得复杂度

---

# 6. Cache Avalanche

普通缓存可以采用：

Base TTL
+
Small Random Jitter

降低大量 Key 同时失效风险。

但是：

Waiting Qualification
Rate Limit Window

等业务时间不能为了 TTL Jitter 被改变。

---

# 7. Serialization

Redis Value 应：

- 结构清晰
- 易调试
- 足够稳定
- 尺寸合理

不要直接缓存完整大型 Entity，
仅仅因为序列化方便。

只保存当前查询真正需要的数据。

序列化格式变化时考虑：

- Existing Keys
- TTL
- Backward Compatibility
- 是否允许自然过期
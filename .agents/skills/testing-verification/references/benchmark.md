# EventHub Benchmark 参考规则

仅当任务涉及：

- JMeter
- Performance
- QPS
- Throughput
- P95 / P99
- Performance Optimization
- Performance Claim

时读取。

---

# 1. Benchmark 与 Correctness 分离

`./mvnw verify`

负责：

Correctness Verification。

JMeter：

负责真实性能测试。

JMeter 不进入普通：

`mvnw verify`。

Unit Test Green：

不能证明 Performance。

高 TPS：

不能证明 Correctness。

---

# 2. Benchmark 目录

真实性能脚本维护在：

`benchmark/jmeter/`

相关执行脚本：

`scripts/benchmark/`

不要把大型 Benchmark 隐藏进普通 Maven Test 生命周期。

---

# 3. 什么时候运行 Benchmark

重点场景：

- Performance 阶段
- FLASH 核心实现完成
- Lua 重大修改
- Rate Limit 调整
- Consumer Concurrency 调整
- Core SQL Optimization
- Release Candidate

简单 DTO 修改：

不需要完整压测。

---

# 4. 测试前先定义问题

例如：

NORMAL：

Conditional UPDATE 能支持怎样的吞吐？

FLASH：

Redis Lua 是否成功削峰？

RabbitMQ：

Consumer 是否形成积压？

Rate Limit：

是否保护下游？

不要先跑一个 JMeter Plan，
再看数字猜瓶颈。

---

# 5. 至少记录

每次有意义的 Benchmark 至少记录：

- Scenario
- Concurrency
- Duration
- Data Volume
- Environment
- QPS / Throughput
- Error Rate
- P95
- P99

根据任务还可以记录：

- CPU
- JVM
- MySQL
- Redis
- RabbitMQ
- Connection Pool
- Queue Depth

---

# 6. Performance 不能牺牲 Correctness

例如：

Stock = 100

Requests = 10000

即使吞吐很高，

如果生成：

101 张有效 Ticket

Benchmark 失败。

压测完成后根据场景检查：

- Oversell
- Duplicate Order
- Duplicate Ticket
- Inventory Invariant
- Reservation Backlog
- Outbox Backlog
- DLQ
- Error State

---

# 7. 优化前后必须可比较

进行 Before / After Benchmark 时：

尽可能保持：

- Hardware
- Environment
- Scenario
- Data Volume
- Concurrency
- Duration
- JMeter Plan

一致。

否则：

不能轻易声称性能提升百分比。

---

# 8. 不伪造数据

只有实际执行得到的数据：

才能进入：

- README
- docs
- Resume
- Interview Material

理论估算：

必须明确写成目标或估算。

不能写成：

真实 Benchmark Result。

没有运行：

就明确说没有真实数据。

---

# 9. 性能结论需要证据链

推荐：

发现问题
→ Baseline Benchmark
→ Metrics / Profile
→ 找到瓶颈
→ 修改
→ Same Scenario Benchmark
→ Compare

不要：

修改代码
→ 感觉更快
→ 写“性能提升 50%”。
---
name: testing-verification
description: >
  用于 EventHub 中涉及 JUnit 5、Mockito、MockMvc、Spring 集成测试、
  MySQL/Redis/RabbitMQ 真实依赖验证、并发与幂等测试、故障场景、
  回归验证以及 JMeter Benchmark 的任务。
  当开发任务需要证明业务正确性、事务原子性、并发安全、
  消息幂等、最终一致性或真实性能表现时使用。
---

# EventHub Testing & Verification

本 Skill 定义 EventHub 测试与验证任务的标准执行流程。

主要处理：

- Unit Test
- Web / Controller Test
- Integration Test
- MySQL / Redis / RabbitMQ Verification
- Business Invariant
- Concurrency / Idempotency
- Failure Scenario
- Regression
- JMeter Benchmark

本 Skill 不替代：

- `AGENTS.md`
- `eventhub-development`
- Database / Redis / RabbitMQ 专业 Skill
- 业务模块本身的正确性规则

测试的目标不是：

“Tests Green”。

而是：

证明当前修改影响的重要业务事实仍然成立。

---

# 1. 先明确要证明什么

写测试前先回答：

如果当前实现有 Bug，
最严重的业务错误会是什么？

例如：

NORMAL 抢票：

- Oversell
- 库存已扣但订单未创建
- 库存守恒被破坏

支付：

- 重复支付产生重复副作用
- Pay 和 Timeout 同时成功

RabbitMQ：

- Duplicate Delivery 创建重复 Order
- DB 已提交但 ACK 前宕机导致重复业务结果

先确定：

Business Invariant

再决定测试方式。

---

# 2. 选择最小必要测试层次

优先按照：

Unit
→ Module / Component
→ Application Integration
→ Concurrency / Failure
→ Benchmark

选择测试层次。

不要所有测试都：

`@SpringBootTest`

也不要所有外部组件都：

Mock。

原则：

纯 Java 逻辑
→ Unit

HTTP Contract
→ MockMvc / Web Test

SQL / Constraint / Transaction
→ Real MySQL

Lua / TTL / ZSet
→ Real Redis

MQ Reliability
→ Real RabbitMQ

跨模块 Use Case
→ App Integration

真实吞吐
→ JMeter

---

# 3. 识别任务类型并读取对应 Reference

## Unit / Controller / Mock

涉及：

- JUnit
- Mockito
- MockMvc
- Validation
- Security Web Layer
- Clock
- Test Structure

读取：

`references/unit-and-web-testing.md`

## Infrastructure Integration

涉及：

- Flyway
- MySQL
- Redis
- Lua
- RabbitMQ
- Docker Compose
- Test Data
- Test Fixtures

读取：

`references/infrastructure-integration.md`

## Business Invariants

涉及：

- NORMAL
- FLASH
- Payment
- Cancel
- Timeout
- Refund
- WaitingList
- Rate Limit
- Outbox

读取：

`references/business-invariants.md`

## Concurrency / Idempotency / Failure

涉及：

- Concurrent Request
- Race Condition
- Duplicate Request
- Duplicate MQ
- Compensation
- Redis Failure
- MQ Failure
- Flaky Test

读取：

`references/concurrency-and-failure.md`

## Benchmark

涉及：

- JMeter
- QPS
- P95 / P99
- Performance Regression
- Optimization Comparison
- Performance Claims

读取：

`references/benchmark.md`

一个任务可能需要多份 Reference。

只加载当前验证目标真正需要的内容。

---

# 4. 不使用错误的测试证明错误的事情

Mockito 不能证明：

- SQL 正确
- Lua 原子
- RabbitMQ Routing 正确
- MySQL Transaction 真实回滚

MockMvc 不能证明：

库存不会超卖。

JUnit Integration Test 绿色不能证明：

高并发性能足够。

JMeter TPS 很高也不能证明：

业务正确。

测试手段必须与要证明的问题匹配。

---

# 5. 核心基础设施使用真实组件

EventHub V1 的核心集成验证使用真实：

- MySQL
- Redis
- RabbitMQ

通过项目：

`docker-compose.yml`

提供开发和测试基础设施。

V1 暂不引入 Testcontainers。

核心 MySQL 行为不使用 H2 作为最终证明。

详细规则见：

`references/infrastructure-integration.md`

---

# 6. 幂等必须通过重复证明

如果实现声称：

“这个操作是幂等的”

测试必须主动制造重复。

例如：

- 重复 Pay
- 重复 Cancel
- 重复 Refund
- 同一已冻结业务 Key 重复 Message（FLASH exact key 尚未冻结）
- 重复 Compensation

目标：

请求可以重复到达，

但：

有效业务副作用只发生一次。

---

# 7. 并发必须通过真实竞争证明

顺序 for 循环：

不等于并发测试。

并发场景使用：

- ExecutorService
- CountDownLatch
- CyclicBarrier
- CompletableFuture
- 其他明确同步机制

制造真实竞争。

测试最终：

Business State

而不是只检查 Thread 是否执行结束。

详细规则见：

`references/concurrency-and-failure.md`

---

# 8. 时间逻辑必须可测试

EventHub 包含：

- WAIT_PAY 15 分钟
- WaitingList Qualification（`FUTURE / PLANNED`，精确时长尚未冻结）
- Sales Window
- Rate Limit Window
- TTL

核心业务时间逻辑允许使用：

`java.time.Clock`

提高可测试性。

不要让普通自动化测试真实等待 15 分钟。

也不要为了测试改变生产业务语义。

---

# 9. 测试不能破坏生产设计

禁止为了让测试更容易：

- 删除 Unique Constraint
- 弱化状态机
- 绕过 Transaction
- 关闭并发保护
- 增加危险生产 API
- 改变业务规则

允许合理的：

- Dependency Injection
- Clock
- Configuration
- Test Fixture
- Test-only Support

测试辅助能力必须位于：

`src/test`

不得污染生产接口。

---

# 10. 局部验证优先

实现完成后先运行与修改最相关的测试。

例如：

Inventory
→ Inventory Tests

Order
→ State / Idempotency Tests

ApplicationService
→ App Integration Test

Lua
→ Redis Integration Test

RabbitMQ
→ MQ Reliability Test

局部验证通过后再扩大验证范围。

---

# 11. 项目级 Verify

重要：

- Java
- Spring
- Maven
- Database
- Configuration
- 核心业务

修改，在环境允许时执行：

`./mvnw verify`

`verify` 用于：

Correctness Verification。

JMeter Benchmark：

不进入普通 `mvnw verify`。

如果完整验证无法执行：

必须明确记录：

- 已验证内容
- 未验证内容
- 原因

---

# 12. 禁止为了 Green Build 弱化合法测试

测试失败时先判断：

- Production Bug
- Test Bug
- Environment Problem
- Flaky Test
- Obsolete Test

禁止为了让 Build 变绿而：

- 删除合法 Test
- 随意 `@Disabled`
- 放宽正确 Assertion
- 吞异常
- 删除并发验证
- 把业务测试退化成 HTTP 200

真实 Bug：

修 Production Code。

---

# 13. 验证报告必须说明“证明了什么”

最终不要只汇报：

`42 tests passed`

应该说明：

测试证明了哪些关键业务事实。

例如：

“100 个库存面对 500 个真实并发请求，
最终成功锁定 100 个，
availableStock = 0，
未出现 Oversell。”

如果某些组件或 Benchmark 没有运行：

必须明确说明不能得到哪些结论。

---

# 14. 文档维护

只有验证事实发生重要变化时才更新 docs。

例如：

- 新增重要 Test Strategy
- Failure Scenario
- Benchmark Result
- 性能优化前后数据
- 测试基础设施变化

真实性能结果维护在：

`docs/benchmark/`

当前开发完成度同步：

`docs/development/progress.md`

禁止把未执行的 Benchmark 写成真实性能结果。

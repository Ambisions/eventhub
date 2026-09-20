# EventHub Unit 与 Web Testing

仅当任务涉及：

- JUnit 5
- Mockito
- MockMvc
- Controller
- Validation
- Security Web Layer
- Clock
- Unit Test Structure

时读取。

---

# 1. Unit Test

Unit Test 适合：

- Domain Rule
- State Check
- Parameter Validation
- Result Conversion
- Pure Business Branch
- Service 内纯 Java 逻辑

优先：

JUnit 5。

Unit Test 应：

- Fast
- Independent
- Repeatable
- 不依赖外部基础设施

一个纯 Java 方法：

不要为了测试它启动完整 Spring Context。

---

# 2. Mockito

Mockito 用于隔离真正的协作者。

例如测试一个真实由 App 拥有的跨领域 ApplicationService 时，可以 Mock：

- Order API
- Inventory API
- 该 Use Case 实际依赖的 Module API

验证：

- 已冻结的 Orchestration
- Branch
- Failure Propagation

当前 Phase 6 NORMAL Order Lifecycle 是窄例外，测试应直接验证 Order Service
拥有的事务编排，不应虚构 `PaymentApplicationService`。

Mockito 不能证明：

- SQL
- Transaction
- Lua
- MQ Delivery
- Database Constraint

不要：

Everything Mocked
→ Everything Correct。

---

# 3. 不滥用 @SpringBootTest

以下情况适合完整 Spring Context：

- ApplicationService
- Spring Transaction
- Security Integration
- Cross-module Use Case
- Spring Configuration

普通 Domain Logic：

不需要完整启动应用。

尽量使用最小必要测试 Context。

---

# 4. MockMvc

Controller / HTTP 测试重点验证：

- Request Binding
- Path / Query / Body
- Validation
- Authentication
- Authorization
- HTTP Status
- Error Mapping
- Response Contract

不要用：

HTTP 200

作为核心业务正确性的唯一证明。

---

# 5. Clock

核心时间逻辑允许注入：

`java.time.Clock`

生产：

真实系统时间。

测试：

`Clock.fixed(...)`

例如：

Order expireTime = 12:15

测试 Clock = 12:14
→ 未超时

Clock = 12:16
→ 已超时

避免：

`Thread.sleep(15 minutes)`

也不要为此构建复杂自研时间框架。

---

# 6. 不修改业务时间语义

测试可以控制：

Clock。

但不能为了测试方便把：

WAIT_PAY 15 分钟

偷偷改成：

15 秒业务规则。

测试应该模拟时间变化，
而不是修改业务定义。

---

# 7. 测试命名

测试名称应表达：

场景
+
预期结果。

例如：

`shouldRejectPaymentWhenOrderAlreadyTimedOut`

比：

`testPay2`

更容易理解。

命名应帮助定位失败的业务场景。

---

# 8. Unit Test 不依赖执行顺序

禁止：

Test A 建数据
→ Test B 使用。

每个 Unit Test 应独立准备自己的：

Input
Mock Behavior
Expected Result。

测试顺序变化：

不应影响结果。

# EventHub App 模块开发规则

> 版本：V1.0
>
> 本文件适用于 `eventhub-app`。
> 本文件补充仓库根目录 `AGENTS.md`。
> 未特别规定的事项继续遵守仓库级规则。
>
> `eventhub-app` 是 EventHub 的 Composition Root、HTTP 接入层和跨领域业务编排层。
> 本模块负责组装和编排，不承载领域内部业务规则。

---

# 1. 模块职责

`eventhub-app` 负责：

- Spring Boot 启动
- HTTP Controller
- ApplicationService
- 跨领域 Use Case 编排
- 应用级 Spring Configuration
- Security 接入
- Filter
- 全局异常处理
- Scheduler 入口
- 应用级集成测试
- 业务模块最终组装

本模块不是独立业务领域。

库存规则属于 Inventory。

订单状态机属于 Order。

Ticket 生命周期属于 Ticket。

用户和活动领域规则分别属于对应模块。

不要把无法判断归属的业务代码默认放入 `eventhub-app`。

---

# 2. 模块调用边界

`eventhub-app` 调用业务模块时，只使用目标模块公开的：

`api`

package。

允许：

ApplicationService
→ Module API

禁止：

ApplicationService
→ Mapper

ApplicationService
→ Repository

ApplicationService
→ 其他模块 Service 实现类

如果公开 API 无法支持合理 Use Case，应讨论扩展 API，而不是绕过模块边界。

---

# 3. Controller 边界

所有 HTTP Controller 位于：

`eventhub-app`

Controller 负责：

- HTTP 参数绑定
- Validation
- 当前用户身份接入
- 调用业务能力
- HTTP Response 转换

Controller 不负责：

- 领域业务规则
- 状态机
- 库存算法
- 事务编排
- 幂等实现
- Redis Lua
- MQ 可靠性
- Mapper / Repository 操作

简单、单领域且无需流程编排的 Use Case 可以：

Controller
→ Module API

例如：

UserController
→ User API

涉及多个模块、多步骤状态变化、跨领域事务或失败协调时必须：

Controller
→ ApplicationService
→ Module APIs

禁止 Controller 自己调用多个 Module API 完成复杂流程。

---

# 4. ApplicationService

ApplicationService 围绕明确的跨领域 Use Case 组织。

对于遵循仓库级默认编排规则的复杂跨领域 Use Case，可以建立命名明确的
ApplicationService。

Phase 6 NORMAL Order Lifecycle 是已冻结的窄例外：Reserve、Pay、Cancel 和
Timeout 由 `eventhub-order` 拥有事务编排；Phase 7 Pay-to-Ticket 与 Phase 9
Refund 是该生命周期的冻结窄扩展。App 仅提供 Controller、Security、Scheduler、
配置、Runtime Adapter 与 Composition Root。Refund Controller 直接委托
`OrderCommandApi`，App 不持有 Refund transaction。WaitingList 的未来事务所有者
尚未冻结。这些例外不构成跨领域流程的通用先例。

禁止创建系统级超级 Service，例如：

`EventHubService`

ApplicationService 可以：

- 调用多个 Module API
- 控制业务执行顺序
- 定义合理事务边界
- 汇总跨领域结果
- 协调失败处理

ApplicationService 不应重新实现各领域内部规则。

---

# 5. 本地事务边界

EventHub 当前运行于：

模块化单体
+
单 JVM
+
单 MySQL

对于只涉及同一 MySQL 的同步跨模块 Use Case，如果多个数据库变化必须共同成功或共同失败：

事务边界默认由对应跨领域 ApplicationService 定义；已冻结的领域生命周期
例外以其 Contract 为准。

当前 Phase 6 NORMAL 抢票由 Order Service 建立本地事务：

Order API / Order Service

→ INSERT `WAIT_PAY` Order

→ Inventory Public API
执行条件库存更新

如果两步必须原子成功或失败，应处于同一个 Spring 本地事务中。

Phase 9 Refund 同样由 Order Service 持有 `READ_COMMITTED` 本地事务，按
`Order → Ticket → Inventory` 执行；Ticket 与 Inventory 以 `MANDATORY` 参与。

跨 Maven Module：

不等于：

分布式事务。

不要因为代码位于不同模块就引入分布式事务框架。

---

# 6. 事务传播

默认保持简单事务传播。

未经明确必要性，不要随意使用：

- `REQUIRES_NEW`
- `NESTED`
- 多 TransactionManager
- 手工事务嵌套

如果确实需要改变事务传播方式，必须先确认：

- 默认事务为何不能满足
- 外层回滚时会发生什么
- 是否可能产生部分成功
- 是否破坏 Use Case 原子性

---

# 7. MySQL 事务不能覆盖 Redis / RabbitMQ

不要认为外围存在：

`@Transactional`

就可以原子覆盖：

MySQL
+
Redis
+
RabbitMQ

以下都是必须处理的真实情况：

Redis 成功
→ MySQL 失败

MySQL 成功
→ MQ 尚未可靠发送

跨组件一致性继续遵守仓库级：

- Outbox
- Retry
- Idempotency
- Compensation
- Reconciliation

不要伪造不存在的强一致性。

---

# 8. Reservation 编排

Reservation 设计必须区分：

NORMAL

和：

FLASH

NORMAL：

Order Service 在同一 Local Transaction 中：

INSERT `WAIT_PAY` Order
→ Inventory Public API 执行 MySQL 条件 UPDATE

FLASH：

Redis Lua
→ Reservation
→ RabbitMQ
→ 异步订单处理

不要为了代码复用把 NORMAL 和 FLASH 强行统一。

库存并发规则属于 Inventory，订单状态规则属于 Order。

App 默认负责复杂跨领域流程编排；当前 NORMAL Order Lifecycle 例外只负责
HTTP / Security / Scheduler 接入与运行时组装，不拥有 Reserve、Pay、Cancel、
Timeout 的事务编排。

---

# 9. 支付、取消与退款

当前 Phase 6 支付与取消由 `eventhub-order` 拥有本地事务，并按固定顺序调用：

支付：`WAIT_PAY → PAID` → Inventory `locked → sold`

取消：`WAIT_PAY → CANCELLED` → Inventory `locked → available`

只有 Order 条件状态更新成功，才允许执行一次库存副作用。

退款为 `CURRENT / FROZEN / IMPLEMENTED`：只有 Order Owner 可调用现有 Refund
HTTP endpoint，Controller 从 JWT principal 取得 userId 并委托
`OrderCommandApi`。Order Service 持有事务并按 `Order → Ticket → Inventory`
完成 `PAID → REFUNDED`、Ticket `VALID → REFUNDED` 和 Inventory
`sold → available`；同一 Session 的历史购买资格不恢复。App 不建立
`RefundApplicationService`，也不拥有该事务。

---

# 10. WaitingList

WaitingList capability 为 `FUTURE / PLANNED / NOT_IMPLEMENTED`。具体 Schema、
Redis ZSet、资格时长、唯一约束与跨领域事务所有者尚未冻结；不得把
`WaitingListApplicationService` 写成硬性要求。

---

# 11. Scheduler

Scheduler 位于 `eventhub-app`，但只负责触发任务。

正确结构：

Scheduler
→ ApplicationService / Module API
→ Business Logic

禁止在 `@Scheduled` 方法中直接：

- 操作 Mapper
- 修改订单状态
- 修改库存
- 实现复杂 Redis 流程
- 编排跨领域业务

Scheduler 可能重复执行，因此被触发的业务能力必须具备必要幂等性。

---

# 12. Filter、Security、Handler 与 Config

Filter 只处理请求级基础能力，例如：

- Authentication 接入
- JWT
- RequestId
- MDC
- 请求上下文

不得承载领域业务。

Spring Security 负责认证与授权边界。

GlobalExceptionHandler 负责：

业务异常
→ HTTP Error Response

不得在异常处理器中执行：

- 补偿
- 重试
- 状态修改

`config/` 只用于应用级 Spring Configuration。

不要因为业务类需要注册 Bean，就把领域实现迁入 App。

---

# 13. DTO 边界

HTTP DTO 位于接入层，例如：

`controller/reservation/dto`

模块 API DTO 位于：

对应业务模块的 `api`

两种 DTO 可以根据需要转换。

禁止：

- HTTP 直接暴露数据库 Entity
- 直接返回 Mapper Entity
- 把所有 DTO 放进 `eventhub-common`

HTTP Contract 与 Module Contract 是不同边界。

---

# 14. App 不能成为业务垃圾桶

代码主要属于哪个领域，就应进入哪个业务模块。

App 只保留：

- HTTP 接入
- 跨领域 Use Case
- 应用组装
- 应用级配置
- Scheduler 入口

不要因为 App 可以依赖所有模块，就把领域代码集中到 App。

---

# 15. 跨领域失败检查

修改 ApplicationService 时必须主动考虑：

- 第一步成功、第二步失败
- 本地事务回滚
- 状态竞争
- 重复请求
- Redis 已成功但 MySQL 失败
- MySQL 已成功但 MQ 未可靠发送
- MQ 重复发送
- Consumer 失败或延迟
- 补偿重复执行

纯 MySQL 且要求共同提交：

使用合理 Local Transaction。

跨 Redis / RabbitMQ / MySQL：

使用既定最终一致性机制。

不要只处理 Happy Path。

---

# 16. 集成测试

`eventhub-app/src/test/`

负责验证：

- Controller
- ApplicationService
- Spring Security
- Spring Transaction
- 跨模块流程
- MySQL
- Redis
- RabbitMQ
- 完整核心业务链路

ApplicationService 的重要变更必须根据实际范围增加集成测试。

核心流程至少关注：

NORMAL：
库存变化 + WAIT_PAY + 事务原子性

支付：
PAID + locked → sold + 重复调用

取消：
CANCELLED + locked → available + 重复调用

当前退款：
REFUNDED + sold → available + 历史购买资格不恢复

未来 FLASH（核心方向 `FUTURE / FROZEN / NOT_IMPLEMENTED`）：
Redis + Lua + Reservation + Outbox + MQ + Consumer + 重复消息 + 失败恢复

不要把 HTTP 200 当成完整业务正确性的证明。

---

# 17. 文档维护

以下设计变化时检查并更新对应文档：

- ApplicationService 职责
- Controller API
- 跨领域流程
- 本地事务边界
- NORMAL / FLASH 编排
- Scheduler
- Security 接入

优先维护：

`docs/specification/eventhub-v1.md`

`docs/database/schema.md`

`docs/development/progress.md`

本文件只维护 App 层长期稳定的组装、编排和事务边界规则。

详细 Spring、Transaction、Security 学习内容放入 docs 或对应 Skill。

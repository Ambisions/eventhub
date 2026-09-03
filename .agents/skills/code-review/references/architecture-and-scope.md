# EventHub Architecture 与 Scope Review

仅当修改涉及：

- Maven Module
- API Boundary
- Controller
- ApplicationService
- Package Placement
- Cross-module Dependency
- Refactor
- Scope

时读取。

---

# 1. Module Boundary

检查是否出现：

Module A
→ Module B Mapper

Module A
→ Module B Repository

Module A
→ Module B Internal Service

跨模块调用应通过：

目标模块公开 `api`。

重点检查：

为了少写一个 API

是否绕过了模块边界。

---

# 2. Controller

简单单领域 Use Case：

Controller
→ Module API

允许。

跨领域业务：

Controller
→ ApplicationService
→ Module APIs

Controller 不应该：

- 自己编排多个模块
- 管 Transaction
- 写状态机
- 操作 Mapper / Repository

---

# 3. ApplicationService

ApplicationService 应围绕：

明确 Use Case。

例如：

- Reservation
- Payment
- Cancellation
- Refund

检查是否出现：

`EventHubService`

等超级 Service。

ApplicationService：

负责 Orchestration。

Domain Module：

负责内部 Business Rule。

---

# 4. App 模块

`eventhub-app`

是：

Composition Root
+
HTTP Layer
+
Cross-domain Orchestration

不是：

业务垃圾桶。

如果代码主要属于：

Inventory / Order / Ticket / Event / User

应进入对应模块。

---

# 5. Common 模块

检查是否把业务逻辑塞入：

`eventhub-common`

仅仅为了让多个模块都能调用。

Common 应保持真正通用。

业务规则应拥有明确 Domain Owner。

---

# 6. Dependency Direction

检查：

- Circular Maven Dependency
- 不合理逆向依赖
- 因方便而增加横向模块依赖

优先：

App Orchestration

而不是：

业务模块互相直接耦合。

---

# 7. Scope Creep

检查当前 Diff 是否包含：

- 无关 Refactor
- 无关 Rename
- 未请求技术升级
- 无关依赖升级
- 顺手改变其他模块

如果修改不是完成当前任务所必需：

应考虑从当前 Scope 移除。

---

# 8. Duplicate Implementation

检查是否已经存在：

- Module API
- DTO
- Service
- Repository
- Utility

但当前修改又创建第二套实现。

优先扩展已有设计。

不要制造平行架构。

---

# 9. Premature Abstraction

Review 以下模式：

- 只有一个实现却创建复杂 Factory
- 为未来假设建立 Plugin System
- 无真实变化点却创建多层 Interface
- 为几行代码引入复杂 Strategy

抽象必须解决真实问题。

---

# 10. Evolution Stage

检查实现是否跳过当前阶段。

EventHub 演进：

Baseline
→ Correctness
→ Redis
→ RabbitMQ
→ Reliability
→ Performance

不要在 Baseline 阶段提前塞入完整后续架构。

Review 应尊重当前实际开发阶段。
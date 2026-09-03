---
name: code-review
description: >
  用于 EventHub 开发任务完成后的代码审查、Git diff 检查、
  架构边界检查、业务正确性审查、并发与幂等风险分析、
  MySQL/Redis/RabbitMQ 可靠性审查、安全与测试完整性检查。
  当需要 Review 新功能、Bug 修复、重构、跨模块修改或重要基础设施变更时使用。
---

# EventHub Code Review

本 Skill 定义 EventHub 的标准 Code Review 流程。

Review 的目标不是寻找尽可能多的问题。

目标是：

优先发现可能导致：

- 错误业务结果
- 数据不一致
- 超卖
- 重复订单 / Ticket
- 非法状态转换
- 消息丢失
- 模块边界破坏
- 安全问题
- 难以恢复的线上故障

的真实风险。

本 Skill 不替代：

- `AGENTS.md`
- `eventhub-development`
- Database / Redis / RabbitMQ 专业 Skill
- `testing-verification`

Review 需要结合：

当前 Diff
+
相关代码
+
适用 AGENTS.md
+
必要项目文档

判断。

不要脱离当前修改进行泛化式代码点评。

---

# 1. Review 默认只读

Code Review 默认是只读任务。

Review 阶段可以：

- 阅读 Git Diff
- 阅读相关源码
- 阅读测试
- 阅读 AGENTS.md
- 阅读必要 docs / Skill Reference
- 分析风险
- 输出 Findings

默认不得：

- 修改 Production Code
- 修改 Test
- 修改 Configuration
- 修改 Migration
- 修改 Documentation
- 自动执行“顺手修复”

发现问题以后：

输出 Finding
→ 交由 Main Codex 或用户决定是否修复。

只有用户明确要求：

- Review 并修复
- 修复 Review Findings
- 审查后直接修改

时，才可以进入代码修改阶段。

即使进入修复阶段：

Review Finding

和：

Code Modification

也应保持逻辑上的两个阶段，
不要一边 Review 一边静默修改，从而改变后续 Review 基准。

# 2. 首先确定 Review Scope

开始 Review 前确认：

- 用户要求 Review 什么
- 当前修改涉及哪些文件
- 涉及哪些 Maven Module
- 是否是新功能、Bug Fix、Refactor 或 Infrastructure Change
- 是否涉及核心业务规则
- 是否涉及 Database / Redis / RabbitMQ / Concurrency
- 是否存在跨模块 Use Case

优先检查：

Git Diff

以及 Diff 直接影响的上下文。

不要默认扫描整个仓库。

如果发现修改影响范围超出 Diff，
再扩大读取范围。

---

# 3. 先读取适用规则

Review 某个模块时，
必须考虑对应 `AGENTS.md`。

例如：

Inventory 修改：

读取 / 使用：

`eventhub-inventory/AGENTS.md`

Order 修改：

`eventhub-order/AGENTS.md`

ApplicationService / Controller：

`eventhub-app/AGENTS.md`

跨模块修改：

可能需要多份局部规则。

Review 的第一问题不是：

“代码写得漂亮吗？”

而是：

“代码有没有违反已经确认的系统不变量？”

---

# 4. 按风险选择 Reference

## Architecture / Scope

涉及：

- Module Boundary
- Controller
- ApplicationService
- Cross-module Dependency
- Package Placement
- Scope Creep
- Duplicate Abstraction

读取：

`references/architecture-and-scope.md`

## Correctness / Concurrency

涉及：

- Inventory
- Order State Machine
- Transaction
- Idempotency
- Race Condition
- Duplicate Request
- Compensation
- WaitingList
- Refund

读取：

`references/correctness-and-concurrency.md`

## Infrastructure

涉及：

- MySQL
- Flyway
- SQL / Index
- Redis / Lua
- RabbitMQ
- Outbox
- Thread Pool
- Performance

读取：

`references/infrastructure-review.md`

## Security / Tests / Quality

涉及：

- Authentication / Authorization
- Input Validation
- Sensitive Logging
- Exception Handling
- Tests
- Documentation
- Maintainability

读取：

`references/security-quality-and-tests.md`

只加载当前 Diff 真正需要的 Reference。

---

# 5. Review 优先级

Review 按以下顺序进行。

## 第一优先级：Business Correctness

检查是否可能导致：

- Oversell
- Duplicate Business Result
- Illegal State Transition
- Inventory Invariant Break
- Historical Purchase Rule Break
- Wrong WaitingList Behavior

## 第二优先级：Concurrency / Idempotency / Consistency

检查：

- Race Condition
- Lost Update
- Duplicate Request
- Duplicate MQ
- Repeated Compensation
- Partial Success
- Transaction Boundary

## 第三优先级：Architecture Boundary

检查：

- Cross-module Mapper Access
- Wrong ApplicationService Responsibility
- Controller Orchestration
- App Becoming Business Dump
- Circular Dependency

## 第四优先级：Failure Handling

检查：

- Redis Down
- MQ Failure
- DB Failure
- ACK Crash Window
- Retry
- DLQ
- Compensation

## 第五优先级：Security / Operability

检查：

- Authorization
- Sensitive Data
- Error Leakage
- Logging
- Metrics

## 第六优先级：Tests / Maintainability

检查：

- Missing Critical Test
- Weak Assertion
- Dead Code
- Excessive Complexity
- Naming / Structure

不要优先花大量时间寻找格式和风格问题，
却漏掉业务正确性问题。

---

# 6. 基于证据提出 Finding

每个 Review Finding 必须能够回答：

1. 问题在哪里？
2. 为什么这是问题？
3. 什么情况下会发生？
4. 会造成什么业务后果？
5. 建议如何修复？

不要只写：

“这里可能有并发问题。”

应具体指出：

哪两个操作竞争，
当前实现为什么不能保证原子性，
可能形成什么非法状态。

如果无法证明某个问题真实存在：

将它表达为：

风险 / 建议进一步验证

而不是确定性 Bug。

---

# 7. 不制造虚假问题

不要为了让 Review 看起来有内容而强行提出：

- 无实际收益的抽象
- 无业务需求的设计模式
- 纯个人风格偏好
- 当前阶段不需要的扩展能力
- 没有证据的性能优化

以下内容不能自动视为问题：

“可以再抽一个接口。”

“这里以后可能扩展。”

“可以使用更高级中间件。”

“这个类有点长。”

只有它真实影响：

Correctness
Reliability
Maintainability
Testability
Performance

时才作为 Finding。

---

# 8. 不扩大技术栈

Review 不应因为看到一个问题就建议：

- Kafka
- Seata
- Redisson Lock
- Elasticsearch
- ShardingSphere
- Spring Cloud

除非用户已经决定改变架构。

优先在 EventHub 当前技术栈和演进阶段内解决问题。

---

# 9. Review 修改本身，而不是重新设计项目

Review 应围绕：

当前 Diff

以及它直接影响的代码。

不要借 Review：

重新设计整个 EventHub。

如果发现重大架构问题：

单独作为 Finding 说明。

不要在没有用户授权的情况下自动扩大 Scope。

---

# 10. 检查测试是否真正证明修改

Review 测试时不只检查：

“有没有 Test”。

还要检查：

Test 是否能够捕获当前风险。

例如：

实现 MQ Consumer Idempotency，

却只有：

Consumer 被调用一次

的测试：

不能证明 Duplicate Delivery 安全。

实现库存并发控制，

只有单线程测试：

不能证明没有 Oversell。

如果需要详细验证策略：

使用 `testing-verification` Skill。

---

# 11. 检查 Diff Hygiene

Review 最后检查：

- 无关文件修改
- 临时日志
- Debug Code
- Commented-out Code
- TODO 是否合理
- 无用 Import
- 未使用类
- 意外配置修改
- Secret / Credential
- Generated File

不要因为这些是小问题就完全忽略，

但它们的严重程度通常低于业务 Correctness。

---

# 12. Finding Severity

EventHub Review 使用：

## Blocker

必须修复后才能认为任务完成。

典型：

- Oversell
- Duplicate Valid Ticket
- Illegal State Transition
- Data Corruption
- Message Loss
- Security Vulnerability
- 严重模块边界破坏导致错误架构

## Major

强烈建议当前任务修复。

典型：

- 缺少关键幂等
- Failure Path 不完整
- Transaction Boundary 错误
- 重要测试缺失
- Redis / MQ Failure Strategy 错误
- 明显性能风险

## Minor

不影响当前核心业务正确性，
但值得改进。

典型：

- Naming
- 小范围 Duplication
- 可读性
- 非关键 Test Improvement
- Documentation Detail

不要把所有问题都标成 Blocker。

---

# 13. Positive Observation 不是主要输出

可以指出少量：

- 正确的设计选择
- 已经覆盖的重要风险

但 Review 主要任务是寻找：

Actionable Findings。

不要用大量：

“这里做得很好”

稀释真正问题。

---

# 14. 没发现问题时明确说明

如果经过合理 Review 没有发现高可信问题：

可以明确写：

未发现 Blocker / Major Finding。

不要为了避免“空 Review”而制造问题。

同时可以说明：

仍然没有验证的部分。

例如：

未执行真实 RabbitMQ Integration Test

则不能声称：

MQ Runtime Behavior 已完全验证。

---

# 15. Review 输出格式

优先按照严重程度输出。

例如：

## Blocker

### [B1] 标题

位置：

`path/to/File.java:line`

问题：

说明具体风险。

触发场景：

说明如何发生。

后果：

说明业务影响。

建议：

给出最小合理修复方向。

## Major

同样格式。

## Minor

仅保留有真实价值的问题。

最后补充：

## Review Summary

- Blocker 数量
- Major 数量
- Minor 数量
- 已 Review 范围
- 未验证范围

---

# 16. Review 完成条件

结束 Review 前确认：

- Diff Scope 已明确
- 适用 AGENTS 已考虑
- 核心 Business Invariant 已检查
- 并发 / 幂等风险已检查
- 事务与失败路径已检查
- 相关 Infrastructure 已按需检查
- Test 是否真正覆盖风险已检查
- 没有把个人风格偏好冒充 Bug
- Findings 有明确证据
- Findings 按严重程度排序

Review 的价值来自：

少量高质量问题

而不是：

大量低价值意见。
# EventHub Agent Dispatch Policy

## 1. Purpose

本文定义 EventHub V1 中 Main Codex 对 Subagent 的派发策略。

目标不是最大化 Agent 数量，而是在真正需要独立 Context 时使用 Subagent，以降低：

* Main Context Pollution
* Existing Implementation 理解错误
* Developer Self-Review Confirmation Bias
* Verification Blind Spot

所有 Agent 使用遵循：

Minimal Context
Minimal Privilege
Minimal Complexity
Progressive Disclosure

Subagent 不是固定开发流水线中的必经步骤。

---

## 2. Agent Architecture

EventHub V1 使用：

```text
Main Codex
= Primary Developer
+ Orchestrator

Subagents
├── explorer
├── reviewer
└── test-reviewer
```

职责：

```text
Explorer
→ 现在真实已经实现了什么？

Reviewer
→ 当前代码变更本身是否正确？

Test Reviewer
→ 当前 Tests 与 Verification Evidence 是否真正证明它正确？
```

三个 Subagent 默认全部 Read Only。

Repository 修改与测试执行统一由 Main Codex 完成。

---

## 3. Core Dispatch Principle

Subagent Dispatch 由风险和 Context Value 驱动，而不是由任务存在本身驱动。

Main Codex 在处理任务时主要判断三类风险：

```text
Understanding Risk
→ Explorer

Change Risk
→ Reviewer

Verification Risk
→ Test Reviewer
```

不得机械执行：

```text
Explorer
→ Reviewer
→ Test Reviewer
```

固定流水线。

某些任务可能一个 Subagent 都不需要。

---

## 4. Task Risk Levels

Main Codex 可以将任务粗略理解为：

### LOW

范围清晰、实现已知、行为风险低。

例如：

* README / Documentation 小型修正
* Validation message
* DTO 小型调整
* 明确位置的简单重命名
* 已知范围的小型 CRUD
* 不改变业务行为的局部整理

默认：

```text
Explorer       NO
Reviewer       NO
Test Reviewer  NO
```

Main Codex 自行完成。

### STANDARD

存在一定业务行为变化，但范围和实现基本清晰。

是否调用 Subagent，根据具体 Understanding Risk、Change Risk 和 Verification Risk 判断。

### HIGH

涉及核心业务性质、并发、一致性、基础设施可靠性、安全或复杂跨模块行为。

通常需要 Reviewer，并在形成重要 Verification Claim 时需要 Test Reviewer。

如果 Existing Implementation Context 不清晰，可先调用 Explorer。

风险等级只是辅助判断，不建立复杂评分系统。

---

# 5. Explorer Dispatch Policy

## 5.1 Dispatch Question

Main Codex 在规划修改之前问：

> 当前是否已经拥有足够可靠的 Existing Implementation Context，可以安全制定修改计划？

如果答案是否定的，考虑调用 `explorer`。

---

## 5.2 Recommended Explorer Scenarios

以下情况通常具有较高 Explorer Value：

### Unknown Call Chain

不知道真实调用链、数据流或基础设施交互。

例如：

```text
FLASH Reservation
```

但尚不清楚当前实现到底经过哪些：

```text
Controller
ApplicationService
Inventory API
Redis
Outbox
RabbitMQ
Order
```

### Cross-Module Exploration

任务涉及多个 Maven Module，且现有跨模块调用方式尚不明确。

### Unknown Root Cause

Debug 时只知道现象，不知道问题实际位于：

```text
Controller
Service
Transaction
Mapper
Redis
RabbitMQ
Database
```

中的哪一层。

### Implementation vs Design Uncertainty

AGENTS / Specification / Architecture Decision 描述了一种设计，但尚未确认当前代码是否真正实现。

### Main Context Expansion Risk

Main Codex 已经需要阅读大量文件才能建立现有实现模型。

此时优先把探索工作隔离到 Explorer Context，再接收压缩后的 Exploration Report。

---

## 5.3 Do Not Dispatch Explorer

满足以下条件时默认不调用：

```text
Known Scope
+
Known Implementation
+
Local Change
```

例如已经明确知道需要修改：

```text
OrderStatus.java
```

增加一个冻结状态，而不存在调用链或架构不确定性。

不要为了“保险”重新扫描 Repository。

---

# 6. Reviewer Dispatch Policy

## 6.1 Dispatch Question

Main Codex 完成一个 Logical Change Batch 后问：

> 如果当前 Diff 存在错误，是否可能破坏重要业务正确性、并发、一致性、架构边界、安全或基础设施可靠性？

如果答案是肯定的，应优先调用 `reviewer`。

---

## 6.2 High-Value Reviewer Scenarios

以下类型原则上属于高 Review Value：

### Core Concurrency

例如：

* Inventory Conditional UPDATE
* Redis Lua
* FLASH Reservation
* Rate Limit
* Concurrent Reservation

### Idempotency / Consistency

例如：

* Consumer Idempotency
* Compensation
* Transactional Outbox
* Eventual Consistency

### Order State Machine

例如：

```text
WAIT_PAY → PAID
WAIT_PAY → CANCELLED
WAIT_PAY → TIMEOUT
PAID → REFUNDED
```

以及非法状态迁移保护。

### Transaction Boundary

尤其涉及：

* Inventory
* Order
* Ticket
* Outbox

等数据在 Local Transaction 中的一致性。

### RabbitMQ Reliability

例如：

* ACK
* Retry
* DLQ
* Publisher Confirm
* Return
* Duplicate Delivery
* Timeout Message

### Redis Execution State

例如：

* stock
* occupied-users
* purchased-users
* reservation-map
* Reservation State

### Database Concurrency / Constraint

例如：

* Conditional UPDATE
* UNIQUE Constraint
* 高并发 Mapper SQL
* 状态条件更新

### Authentication / Authorization

JWT、Spring Security、权限边界发生实质行为变化。

### Important Cross-Module Change

一次 Logical Change 同时影响多个领域模块或 ApplicationService 编排。

---

## 6.3 Do Not Dispatch Reviewer

以下情况通常不需要独立 Review：

* Documentation typo
* Formatting
* Comment-only change
* 无业务行为变化的 rename
* 简单 DTO 调整
* 已知范围的低风险 CRUD
* 明确的机械性小改动

Reviewer 由 Change Risk 驱动，而不是由“存在 Diff”驱动。

---

# 7. Test Reviewer Dispatch Policy

## 7.1 Dispatch Question

Main Codex 问：

> 当前是否准备声称一个重要业务性质已经被测试或验证证明？

如果答案是肯定的，应考虑调用 `test-reviewer`。

Test Reviewer 由 Verification Claim 驱动。

---

## 7.2 High-Value Test Reviewer Scenarios

### Core Concurrency Completion

准备认为以下性质已经成立：

```text
No Oversell
```

或者 FLASH / NORMAL 核心库存流程完成。

### Idempotency / RabbitMQ Reliability Completion

准备认为：

```text
Duplicate Consumer Safe
Consumer Idempotency
```

已经得到证明。

### State Machine Completion

订单状态机核心路径及非法状态迁移准备宣布完成。

### Compensation / Eventual Consistency

尤其需要检查：

* Failure Path
* Duplicate Operation
* Compensation Idempotency
* 最终状态

### Core Development Stage Completion

例如：

```text
NORMAL 完成
Redis / Lua 完成
RabbitMQ Async 完成
Reliability 完成
```

阶段结束前可以进行一次阶段级 Verification Review。

### Before Benchmark Claims

准备进行正式 JMeter Benchmark 或把性能数据写入 README 前，应先确保核心 Correctness Properties 已经获得可信验证。

Performance 不代替 Correctness。

---

## 7.3 Do Not Dispatch Test Reviewer

以下情况默认不需要：

* 普通 DTO
* README
* 小型 Controller 修改
* Validation message
* 普通 CRUD
* 单纯新增一个低风险 Unit Test
* 不形成重要 Verification Claim 的局部调整

存在 Tests 不等于必须调用 Test Reviewer。

---

# 8. Default Agent Sequence

Subagent 不形成固定流水线，但复杂任务可以遵循以下自然依赖：

```text
Task
  ↓
Main 判断 Existing Context 是否足够
  ↓
必要时 Explorer
  ↓
Main Plan
  ↓
Main Implement
  ↓
Main Targeted Verification
  ↓
必要时 Reviewer
  ↓
Main Fix
  ↓
Main Retest
  ↓
必要时 Test Reviewer
  ↓
Main 执行 Required Verification
  ↓
Done
```

Reviewer 通常应在初步实现和 targeted verification 后进行。

Test Reviewer 通常应在主要 Reviewer Findings 已处理后进行，使其审核接近最终版本的 Tests 与 Evidence。

---

# 9. Logical Change Batch

Agent 默认以 Logical Change Batch 为调用粒度。

例如一个 FLASH Reservation Change Batch 可能同时包含：

```text
FlashApplicationService.java
FlashInventoryService.java
RedisKeys.java
flash-reserve.lua
Reservation Tests
```

应该完成一个逻辑批次后统一 Review。

不要：

```text
修改一个文件
→ Reviewer

再修改一个文件
→ Reviewer

再修改 Lua
→ Reviewer
```

Agent Review 的对象是 Feature / Logical Change，而不是每一次文件保存。

---

# 10. Re-Dispatch Policy

不得形成无限 Review Loop。

例如：

```text
Reviewer
→ Main Fix
→ Reviewer
→ Main Fix
→ Reviewer
```

只有满足以下情况时才考虑重新 Dispatch：

* Fix 改变了原 Finding 对应的核心业务行为
* Fix 明显扩大了 Change Scope
* Fix 新增了高风险并发、一致性或基础设施逻辑
* Main Codex 无法可靠确认原 Finding 已真正解决

简单、局部、直接对应 Finding 的修正，可以由 Main Codex：

```text
Inspect Diff
→ Targeted Test
```

后继续推进。

---

# 11. Escalation Policy

Subagent 可以发现超出自己主要职责的问题，但默认不自行 Spawn 其他 Agent。

## Explorer → Reviewer

Explorer 如果发现疑似 Production Correctness 问题：

记录：

```text
Risk
或
Needs Verification
```

Main Codex 决定是否 Dispatch Reviewer。

## Reviewer → Test Reviewer

Reviewer 如果认为 Production Code 可能正确，但缺乏可信测试证明：

记录 Testing Risk。

Main Codex 决定是否 Dispatch Test Reviewer。

## Test Reviewer → Reviewer

Test Reviewer 如果测试证据暴露疑似 Production Bug：

记录：

```text
Implementation Concern
```

Main Codex 决定是否重新 Dispatch Reviewer。

---

# 12. Orchestration Authority

Main Codex 是 EventHub V1 默认且唯一的 Orchestrator。

默认：

```text
Main
→ Explorer

Main
→ Reviewer

Main
→ Test Reviewer
```

不默认：

```text
Explorer
→ Reviewer

Reviewer
→ Test Reviewer
```

Subagent 可以建议需要其他角色进一步处理，但是否派发由 Main Codex 决定。

原因：

* 控制 Context
* 控制 Token
* 控制 Agent 数量
* 避免 Nested Delegation
* 保持修改责任清晰
* 保持开发流程可追踪

---

# 13. Parallel Dispatch Policy

V1 默认不并行 Fan-out：

```text
Main
├── Explorer
├── Reviewer
└── Test Reviewer
```

三个同时执行。

原因是三个角色通常存在自然依赖关系。

只有未来存在真正相互独立、并行收益明显的只读任务时，再考虑并行 Subagent。

V1 优先：

```text
Sequential
+
Purpose-Driven Dispatch
```

---

# 14. Dispatch Matrix

| Scenario                 | Explorer      | Reviewer          | Test Reviewer               |
| ------------------------ | ------------- | ----------------- | --------------------------- |
| README / typo            | No            | No                | No                          |
| 已知位置的小型 CRUD             | No            | Usually No        | No                          |
| Existing Call Chain 不清楚  | Yes           | No                | No                          |
| Unknown Root Cause       | Yes           | Later if needed   | Later if needed             |
| 跨模块核心 Feature            | Usually       | Risk-based        | Claim-based                 |
| MySQL Conditional UPDATE | Context-based | Yes               | Yes at completion           |
| Redis Lua / FLASH        | Context-based | Yes               | Yes                         |
| RabbitMQ Consumer        | Context-based | Yes               | Yes                         |
| Transactional Outbox     | Usually       | Yes               | Yes                         |
| 核心订单状态机                  | Context-based | Yes               | Yes at completion           |
| 单纯新增 Unit Test           | No            | No                | Usually No                  |
| 核心 Feature 准备宣布完成        | No            | Diff-based        | Yes                         |
| JMeter Benchmark 前       | No            | Usually No        | Verify correctness evidence |
| 复杂未知 Bug                 | Yes           | Later if required | Later if required           |

矩阵用于辅助判断，不覆盖 Main Codex 对实际任务风险的判断。

---

# 15. Minimal Dispatch Algorithm

Main Codex 可以使用以下最小判断流程。

## Step 1 — Context

```text
是否已经拥有安全规划所需的 Existing Implementation Context？
```

NO：

```text
→ Explorer
```

YES：

```text
→ 不调用 Explorer
```

## Step 2 — Implement

Main Codex 完成一个 Logical Change Batch，并执行必要的 targeted verification。

## Step 3 — Change Risk

```text
是否影响核心业务正确性、
并发、幂等、一致性、状态机、
事务、安全、基础设施可靠性
或重要跨模块边界？
```

YES：

```text
→ Reviewer
```

NO：

```text
→ 不调用 Reviewer
```

## Step 4 — Verification Claim

```text
是否准备声称某个重要业务性质已经得到证明，
或者核心开发阶段准备结束？
```

YES：

```text
→ Test Reviewer
```

NO：

```text
→ 不调用 Test Reviewer
```

---

# 16. V1 Frozen Decisions

EventHub V1 冻结以下 Dispatch Decisions：

```text
D01
Subagent Dispatch 由需求与风险驱动，
不建立固定 Explorer → Reviewer → Test Reviewer 流水线。

D02
Explorer 由 Context Uncertainty 驱动。

D03
Reviewer 由 Change Risk 驱动。

D04
Test Reviewer 由 Verification Claim 驱动。

D05
Main Codex 是唯一默认 Orchestrator；
Subagent 默认不自行级联 Spawn 其他 Subagent。

D06
Agent 以 Logical Change Batch 为主要调用粒度，
不按单文件、单次 Save 或每个小型 Fix 反复 Dispatch。

D07
默认顺序为：

必要时 Explore
→ Main Implement + Targeted Verification
→ 必要时 Review
→ Main Fix + Retest
→ 必要时 Test Review
→ Main 完成缺失 Verification

V1 默认不并行 Fan-out。
```

本文属于 Agent Engineering 的 Living Documentation。

未来只有在真实 EventHub 开发实践证明当前 Dispatch Policy 存在明显成本或缺陷时才调整，不为了增加 Agent 数量或流程复杂度而扩展。

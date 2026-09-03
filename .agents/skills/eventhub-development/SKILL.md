---
name: eventhub-development
description: >
  用于 EventHub Java 后端项目中的功能开发、Bug 修复、重构、
  业务流程实现、跨模块修改以及中大型工程任务。
  负责从任务理解、相关 Context 获取、实现计划、编码、
  测试验证、Review 到 Living Documentation 维护的完整开发流程。
---

# EventHub 标准开发流程

本 Skill 定义 EventHub 项目的标准开发工作流。

适用于：

- 新功能开发
- Bug 修复
- 业务流程实现
- 跨模块修改
- 中大型重构
- 数据库 Schema 变化
- Redis / RabbitMQ / 并发相关开发
- 可靠性和性能相关工程任务

普通知识解释、代码阅读或简单问答不需要默认执行完整开发流程。

本 Skill 不替代：

- 仓库级 `AGENTS.md`
- 模块级 `AGENTS.md`
- `docs/` 中的项目事实文档
- Database / Redis / RabbitMQ / Testing / Review 等专业 Skill

实际实现始终以：

当前代码
+
适用的 AGENTS.md
+
当前项目文档

为准。

---

# 1. 理解任务

收到开发任务后，先明确：

- 用户真正希望完成什么
- 当前任务属于新功能、Bug、重构、测试、性能还是其他类型
- 是否涉及核心业务规则
- 是否涉及多个 Maven 模块
- 是否涉及数据库、Redis、RabbitMQ、并发或最终一致性
- 是否可能改变现有架构

不要在任务边界尚未明确时直接修改代码。

如果用户目标已经足够明确，不要为了流程形式重复询问。

只有存在会显著影响实现方案，并且无法通过现有代码、AGENTS 或 docs 判断的问题时，才向用户提问。

---

# 2. 判断任务复杂度

简单、局部、低风险任务可以在完成必要检查后直接实施。

例如：

- 小型 Bug
- 单字段修改
- 简单配置调整
- 局部测试修复
- 文档小改动

中等或复杂任务必须先制定简洁 Plan。

例如：

- 新业务功能
- 跨模块 Use Case
- 数据库 Schema 修改
- NORMAL / FLASH 抢票
- 支付、取消、超时、退款
- Redis Lua
- RabbitMQ
- Transactional Outbox
- 最终一致性
- 并发控制
- 重要重构
- 性能优化

---

# 3. 建立最小必要 Context

不要无目的扫描整个仓库。

根据任务识别真正相关的：

- Maven Module
- AGENTS.md
- docs
- Java 源码
- Test
- Configuration
- Flyway Migration
- Mapper XML
- Redis Lua
- RabbitMQ 配置

只读取完成当前任务真正需要的信息。

优先通过：

已有实现
+
已有测试
+
相关文档

理解项目当前状态。

不要只根据项目说明书或预期架构猜测当前代码已经实现到了哪里。

---

# 4. 加载相关模块规则

如果当前 Codex 会话没有自动加载任务涉及模块的 `AGENTS.md`，则在制定 Plan 和修改代码前主动读取。

涉及库存、Redis 抢票、WaitingList 或限流：

`eventhub-inventory/AGENTS.md`

涉及订单状态机、Reservation、Outbox 或 MQ Consumer：

`eventhub-order/AGENTS.md`

涉及 Controller、ApplicationService、跨领域事务或应用组装：

`eventhub-app/AGENTS.md`

跨模块任务可以同时读取多份模块规则。

例如支付功能可能需要：

`eventhub-order/AGENTS.md`

`eventhub-inventory/AGENTS.md`

`eventhub-app/AGENTS.md`

模块级规则只约束对应作用域。

不要把一个模块的特殊规则错误扩展为整个仓库规则。

---

# 5. 按需读取项目文档

只有任务需要时才读取对应 docs。

常见映射：

总体架构：

`docs/architecture/architecture.md`

模块依赖：

`docs/architecture/module-dependencies.md`

NORMAL / FLASH 抢票：

`docs/architecture/reservation-flow.md`

最终一致性：

`docs/architecture/consistency.md`

数据库：

`docs/database/`

Redis：

`docs/middleware/redis.md`

RabbitMQ：

`docs/middleware/rabbitmq.md`

项目当前进度：

`docs/development/progress.md`

重要架构决策：

`docs/decisions/`

不要因为开始一个任务就一次性加载整个 `docs/`。

---

# 6. 检查当前实现

开始编码前检查：

- 是否已有类似功能
- 是否已有可复用 Module API
- 是否已有 DTO
- 是否已有 Service
- 是否已有 Repository / Mapper
- 是否已有数据库约束
- 是否已有相关测试
- 当前开发阶段已经实现到了哪里

优先扩展和修正现有设计。

不要因为第一次搜索没有找到，就立即创建第二套平行实现。

---

# 7. 制定 Plan

中等或复杂任务必须先形成简洁、可执行的 Plan。

Plan 至少包含：

## 目标

最终需要达到什么业务结果。

## 涉及范围

预计涉及哪些：

- Maven Module
- ApplicationService
- Module API
- Database
- Redis
- RabbitMQ
- Test
- Docs

## 实现步骤

按合理顺序描述核心修改。

## 关键风险

只指出真正重要的风险，例如：

- 并发竞争
- 状态机
- 幂等
- 事务
- 数据一致性
- 数据库约束
- 失败恢复

## 验证方案

说明如何证明本次实现正确。

Plan 应简洁。

不要把 Plan 写成无法维护的大型设计文档。

如果实际探索发现原计划存在错误，应根据真实代码调整 Plan。

---

# 8. 尊重当前架构演进阶段

开始实现前判断 EventHub 当前位于哪个阶段。

项目按照：

Baseline
→ Correctness
→ Redis
→ RabbitMQ
→ Reliability
→ Performance

逐步演进。

不要提前实现尚未进入的阶段。

例如：

当前仍在 NORMAL 抢票阶段，

不要因为最终设计包含 Redis + RabbitMQ，
就直接把完整 FLASH 架构混入当前实现。

先完成并验证当前阶段，再进行下一阶段演进。

---

# 9. 按需使用专业 Skill

如果任务明显涉及专业领域，并且仓库已经存在对应 Skill，应按需加载。

例如：

数据库设计、SQL、Flyway、索引：

`database-development`

Redis、Lua、FLASH 并发：

`redis-concurrency`

RabbitMQ、Outbox、消息可靠性：

`rabbitmq-reliability`

测试设计与验证：

`testing-verification`

代码 Review：

`code-review`

只加载与当前任务真正相关的专业 Skill。

不要因为 Skill 存在就一次性加载全部 Skill。

如果专业 Skill 尚未创建，不得因此阻塞合理开发。

此时继续依据：

AGENTS
+
docs
+
现有代码
+
已有测试

完成任务。

---

# 10. 控制实现范围

开发过程中：

- 保持任务 Scope
- 优先小范围修改
- 遵循现有模块边界
- 遵循现有命名和目录风格
- 复用已有公开 API
- 优先简单、可验证的方案
- 保持代码可读和可测试

不要：

- 顺手大规模重构
- 修改无关模块
- 创建没有明确价值的新抽象
- 为未来可能不存在的需求提前设计扩展点
- 为减少少量重复代码制造错误依赖

如果发现现有架构问题会阻止当前任务正确完成，应先说明问题再进行必要调整。

其他潜在优化只记录建议，不自动扩大当前任务。

---

# 11. 数据库变更流程

涉及 Schema 修改时：

1. 检查已有 Flyway Migration。
2. 检查现有表、约束和索引。
3. 通过新的 Migration 实施正式 Schema 变化。
4. 同步修改 Java 持久化映射。
5. 检查 Mapper / XML。
6. 增加或修改测试。
7. 更新必要数据库文档。

不要只修改：

Java Entity / Mapper

而遗漏真实数据库。

也不要只修改数据库而遗漏 Java 映射。

已经进入稳定阶段的历史 Migration 不应随意重写。

---

# 12. 并发与幂等任务检查

任务涉及以下领域时：

- Inventory
- Reservation
- Order 状态
- 支付
- 取消
- 超时
- 退款
- MQ
- WaitingList
- Compensation
- Reconciliation

必须额外检查：

- 重复请求
- 并发竞争
- 状态转换合法性
- 原子性
- 数据库唯一约束
- 重复消息
- 重复释放
- 重复补偿
- 部分成功
- 异常恢复

不要只实现单线程 Happy Path。

---

# 13. 先进行局部验证

完成实现以后，先执行与修改范围最匹配的快速验证。

例如：

单模块业务修改：

运行对应模块测试。

ApplicationService 修改：

运行相关集成测试。

Mapper / SQL：

验证 SQL 和数据库行为。

Redis Lua：

验证库存边界、重复调用和失败路径。

RabbitMQ：

验证重复消息、异常和幂等。

优先通过聚焦测试快速发现问题。

---

# 14. 再进行项目级验证

对于重要 Java、Spring、Maven、数据库或核心配置修改，在环境允许时执行：

`./mvnw verify`

如果验证失败：

先判断失败是否与当前修改有关。

不得为了让测试通过而删除、跳过或弱化合理测试。

如果因为环境问题无法完整验证，例如：

- MySQL 未运行
- Redis 未运行
- RabbitMQ 未运行
- Docker 不可用

必须明确记录：

- 已执行哪些验证
- 哪些没有执行
- 为什么无法执行

---

# 15. Review

实现和测试完成后检查 Git diff。

重点检查：

- 是否超出任务 Scope
- 是否修改无关文件
- 是否违反模块边界
- 是否出现重复实现
- 是否存在临时代码
- 是否存在异常路径遗漏
- 是否存在明显并发或幂等问题
- 是否遗漏必要测试
- 是否存在不必要依赖

如果仓库已经存在 `code-review` Skill，应根据任务复杂度按需使用。

Subagent 调度暂不由本 Skill 固定。

Agent 体系完成后，可以再扩展这一阶段。

---

# 16. Living Documentation

不是所有代码修改都需要更新架构文档。

发生重要项目事实变化时，应同步维护 docs。

例如：

- 核心业务流程变化
- 数据模型变化
- 模块职责变化
- Redis 设计变化
- RabbitMQ 设计变化
- 一致性方案变化
- 重要架构决策变化
- 得到新的真实性能数据

完成明显开发阶段后更新：

`docs/development/progress.md`

文档只能描述真实实现。

禁止把计划中的功能提前描述为已完成。

---

# 17. 完成检查

宣布任务完成前确认：

- 用户目标已经满足
- 没有违反适用的 AGENTS.md
- 必要代码已经实现
- 核心业务约束没有被破坏
- 相关测试已经执行
- 必要集成验证已经完成
- 未验证内容已经明确说明
- Git diff 没有明显无关修改
- 必要文档已经同步
- 没有偷偷扩大技术栈
- 没有偷偷扩大任务范围

只有部分完成时必须明确说明。

---

# 18. 最终汇报

任务完成后简洁说明：

## 完成内容

实际完成了什么。

## 核心修改

涉及哪些主要模块和文件。

## 重要设计

只有存在重要设计决定时说明。

## 验证结果

说明：

- 执行了哪些测试
- 哪些通过
- 哪些没有执行
- 未执行原因

## 文档更新

说明是否更新 Living Documentation。

## 剩余问题

如果存在：

- 未验证问题
- 已知风险
- 后续任务

必须明确说明。

不要只回复：

“已经完成。”
# EventHub 项目开发规则

> 版本：V1.0
>
> 本文件是 EventHub 仓库级开发规则。
> 所有在本仓库中工作的 Codex Agent 都必须遵守本文件。
>
> 更具体的模块可以通过模块目录中的 `AGENTS.md` 增加局部规则。
> 局部规则只能补充或收紧本文件，不应随意破坏项目的全局架构原则。

---

# 1. 项目定位

EventHub 是一个基于 Java 的高并发活动预约与限量票务平台。

项目主要面向：

- 技术大会
- 线下沙龙
- 展览
- 演出
- 热门活动限量抢票

项目重点不是实现大量 CRUD，也不是为了展示技术栈而堆砌中间件。

核心工程目标是解决：

- 高并发库存控制
- 订单生命周期管理
- Redis / MySQL 数据一致性
- RabbitMQ 异步削峰与可靠消息
- 幂等
- 限流
- 缓存治理
- 补偿与对账
- 系统稳定性
- JVM、数据库和应用性能优化

实现任何功能时，优先级始终为：

业务正确性
→ 数据一致性
→ 模块边界
→ 可读性
→ 可测试性
→ 可解释性
→ 性能优化

不要为了展示技术复杂度而引入当前业务不需要的技术。

---

# 2. 当前技术基线

项目当前使用：

- Java 17
- Spring Boot 3.5.x
- Spring MVC
- Spring Security
- JWT
- Jakarta Validation
- Spring Transaction
- MyBatis-Plus
- MyBatis XML
- MySQL 8.4 LTS
- InnoDB
- HikariCP
- Flyway
- Redis
- Spring Data Redis
- Lettuce
- Redis Lua
- RabbitMQ
- Spring AMQP
- JUnit 5
- Mockito
- MockMvc
- JMeter
- Spring Boot Actuator
- Micrometer
- Docker Compose

普通 CRUD 优先使用 MyBatis-Plus。

以下类型的核心数据库操作优先使用显式 Mapper / MyBatis XML：

- 库存条件 UPDATE
- 并发控制相关 SQL
- 复杂查询
- 需要精确控制 SQL 的场景
- 需要明确分析索引执行情况的 SQL

未经用户明确确认，不要自行替换已经确定的核心技术栈。

---

# 3. 总体架构

EventHub 采用：

Maven 多模块
+
模块化单体

EventHub 不是微服务项目。

最终系统由：

`eventhub-app`

组装所有业务模块，并运行在一个 JVM 中。

最终产物为单个可运行 Spring Boot 应用。

不要因为 Maven 模块较多而自行将系统改造成微服务。

---

# 4. Maven 模块

当前模块固定为：

- `eventhub-common`
- `eventhub-user`
- `eventhub-auth`
- `eventhub-event`
- `eventhub-inventory`
- `eventhub-order`
- `eventhub-ticket`
- `eventhub-notification`
- `eventhub-app`

未经用户明确确认：

不要创建新的 Maven 业务模块。

尤其不要自行增加：

- `eventhub-domain`
- `eventhub-core`
- `eventhub-infrastructure`
- `eventhub-api`
- `eventhub-service`
- `eventhub-redis`
- `eventhub-mq`

或者其他为了抽象而产生的新模块。

---

# 5. 模块依赖规则

必须遵守以下规则。

## 5.1 基本依赖原则

模块依赖必须保持单向。

禁止循环 Maven 依赖。

所有业务模块都可以依赖：

`eventhub-common`

`eventhub-app` 是系统 Composition Root，可以依赖完成业务编排所需要的业务模块。

业务模块只有在存在真实业务依赖时，才能依赖另一个业务模块。

不要提前建立没有实际业务调用需求的模块依赖。

---

## 5.2 跨模块调用

跨模块调用只能通过目标模块公开的：

`api`

package。

禁止跨模块直接访问其他模块的：

- Mapper
- Repository
- Service 实现类
- 内部 Redis 实现
- 内部 MQ 实现
- 内部持久化实现
- 其他未公开基础设施实现

例如：

允许：

`eventhub-app`
→ `eventhub-inventory.api.InventoryService`

禁止：

`eventhub-app`
→ `eventhub-inventory.mapper.InventoryMapper`

Repository 和 Mapper 永远属于自己的业务模块。

---

## 5.3 跨领域流程

跨多个业务领域的 Use Case 原则上由：

`eventhub-app`

中的：

`ApplicationService`

负责业务流程编排。

本领域内部业务规则由对应模块的：

- Domain
- Service

负责。

不要让某一个业务模块逐渐演变成整个系统的业务编排中心。

---

# 6. eventhub-common 使用规则

`eventhub-common` 只允许存放真正跨模块共享的基础能力。

例如：

- 通用异常
- 统一返回结果
- 全局常量
- 真正通用的枚举
- 无业务含义的通用工具
- 请求上下文
- 全局基础抽象

禁止因为多个模块需要使用某段业务代码，就默认将其移动到 common。

原则上不得放入 common：

- 具体业务 Service
- 具体业务 Repository
- Mapper
- 业务 Entity
- 某个领域专属 DTO
- 某个领域专属状态机
- 某个领域专属业务规则
- 为解决循环依赖而被迫搬迁的业务代码

如果出现循环依赖：

不要通过把业务代码移动到 common 来规避问题。

应重新检查模块职责和依赖方向。

---

# 7. eventhub-app 职责

`eventhub-app` 是整个系统的：

- Spring Boot 启动入口
- HTTP 接入层
- Composition Root
- 跨领域业务编排层
- 全局配置入口

所有 Controller 统一位于：

`eventhub-app`

Controller 主要负责：

- HTTP Request 接收
- 参数转换
- Validation
- 当前用户身份信息接入
- 调用 ApplicationService
- 返回 HTTP Response

Controller 不负责复杂业务逻辑。

---

# 8. ApplicationService 规则

跨领域 Use Case 使用 ApplicationService 编排。

例如：

`ReservationController`
→ `ReservationApplicationService`
→ Event API
→ Inventory API
→ Order API

禁止：

Controller
→ Mapper

禁止：

Controller
→ Repository

原则上也不要让 Controller 自己调用多个领域 Service 完成复杂业务编排。

ApplicationService 负责：

业务流程。

Domain Service / Module Service 负责：

本领域业务规则。

Controller 负责：

HTTP。

三者职责不得混淆。

---

# 9. 数据一致性基本原则

整个 EventHub 始终遵守：

MySQL
=
Source of Truth

Redis
=
缓存层 / 高并发执行层

RabbitMQ
=
异步传输层 / 削峰层

Redis 不是最终持久化事实来源。

RabbitMQ 也不是业务状态最终事实来源。

跨 Redis、RabbitMQ、MySQL 的一致性问题，不使用“万能分布式锁”解决。

优先考虑：

Local Transaction
+
Transactional Outbox
+
Retry
+
Idempotency
+
Compensation
+
Reconciliation

RabbitMQ 可靠性目标采用：

At-Least-Once
+
Idempotent Consumer

不要为了追求理论上的 Exactly Once 引入不必要的复杂度。

---

# 10. 架构演进原则

EventHub 必须按照以下顺序逐步演进：

Baseline
↓
Correctness
↓
Redis
↓
RabbitMQ
↓
Reliability
↓
Performance

不要跳过简单版本，直接实现最终复杂架构。

每次引入新的技术时，都必须能够回答：

1. 前一个版本存在什么真实问题？
2. 当前技术为什么能够解决这个问题？
3. 当前方案带来了什么新的复杂度或问题？
4. 下一阶段为什么需要继续演进？

项目核心演进路线是：

MySQL 条件 UPDATE
↓
Redis 前置库存
↓
Redis Lua 原子操作
↓
RabbitMQ 异步削峰
↓
Transactional Outbox
↓
Consumer Idempotency
↓
Retry / DLQ
↓
Compensation
↓
Reconciliation
↓
限流 / 缓存治理 / 性能优化

不要为了赶最终架构而破坏这条学习和验证路线。

---

# 11. V1 禁止主动引入的技术

除非用户明确修改项目设计，否则不要主动引入：

- Spring Cloud
- Nacos
- Gateway
- Feign
- Seata
- Kafka
- RocketMQ
- Elasticsearch
- MongoDB
- ShardingSphere
- XXL-Job
- 复杂分布式锁体系
- 完整复杂 DDD
- CQRS
- Event Sourcing

不要因为某项技术更“高级”就替换当前已经确定的简单方案。

---

# 12. 数据库与 Flyway 规则

数据库 Schema 使用 Flyway 管理。

Migration 位于：

`eventhub-app/src/main/resources/db/migration/`

数据库正式 Schema 变更必须通过 Flyway migration 完成。

数据库结构的实现事实来源是：

Flyway Migration

数据库设计文档位于：

`docs/database/`

文档用于解释：

- 表为什么这样设计
- 字段含义
- 表之间的关系
- 唯一约束
- 索引
- 并发控制相关设计

不要只修改数据库文档，而不修改 migration。

不要通过 Navicat 等数据库客户端直接修改正式 Schema 后不留下 migration。

Navicat 可以用于：

- 查看数据库
- 查看表结构
- 查看索引
- 查看数据
- 执行查询
- SQL 调试
- EXPLAIN 分析

但正式 Schema 变更必须回到 Flyway。

已经执行并进入稳定阶段的历史 migration 原则上不要随意修改。

Schema 发生新的变化时，优先新增 migration。

---

# 13. 测试分层

EventHub 测试分成三层。

## 13.1 模块测试

各 Maven 模块：

`src/test/`

主要负责：

- Service
- Domain Rule
- 状态转换
- Repository 行为
- 边界条件
- 幂等逻辑
- 纯业务规则

主要使用：

- JUnit 5
- Mockito

---

## 13.2 集成测试

`eventhub-app/src/test/`

主要负责：

- SpringBootTest
- MockMvc
- Controller
- ApplicationService
- 跨模块业务链路
- MySQL
- Redis
- RabbitMQ
- 完整核心流程

---

## 13.3 性能测试

性能测试位于：

`benchmark/jmeter/`

用于真实高并发压测。

性能结论必须来源于真实测试。

禁止在：

- README
- 系统说明书
- 简历
- benchmark 文档

中伪造：

- QPS
- P95
- P99
- Error Rate
- MySQL 压力
- Redis 性能
- RabbitMQ 积压
- JVM 指标

没有测试过的数据必须明确标记为：

未测试

而不是进行估算后当作真实结果。

---

# 14. Codex 接到任务后的工作方式

接到任务以后，不要默认立即修改代码。

首先完成：

1. 明确当前任务目标。

2. 阅读适用于当前目录的 `AGENTS.md`。

3. 判断任务属于哪个业务模块或跨领域 Use Case。

4. 阅读与当前任务直接相关的代码。

5. 只在必要时读取对应 docs。

6. 检查现有实现和模块依赖。

7. 判断是否存在已有实现可以复用。

不要无目的扫描整个仓库。

不要为了完成局部任务，一次读取：

- 所有 docs
- 所有 Maven 模块
- 所有测试
- 所有配置

只加载当前任务需要的 Context。

---

# 15. 简单任务和复杂任务

简单、局部、低风险任务可以直接处理。

例如：

- 修复明显拼写
- 修改单个配置
- 增加一个简单字段
- 修复局部测试

中等或复杂任务开始实现之前，必须先给出简短计划。

例如：

- 新业务功能
- NORMAL 抢票
- FLASH 抢票
- Redis Lua
- MQ
- Outbox
- 支付
- 退款
- 跨模块修改
- 数据库 Schema 修改
- 核心架构调整

计划至少说明：

- 目标
- 涉及模块
- 预计修改的核心文件
- 核心实现方案
- 需要验证的关键风险

不要输出过度冗长的计划。

计划的目的是减少错误方向，而不是代替实现。

---

# 16. 修改代码时的原则

优先：

- 小范围修改
- 保持现有模块边界
- 保持现有命名风格
- 复用已有实现
- 修改与当前任务直接相关的代码
- 保证实现容易解释和测试

避免：

- 顺手大规模重构
- 修改与当前任务无关的模块
- 创建没有明确价值的新抽象
- 提前设计未来可能永远不会使用的扩展点
- 为消除少量重复代码制造模块耦合
- 为了设计模式而设计

如果发现现有架构确实存在问题：

先说明问题。

不要未经确认直接进行大范围架构重构。

---

# 17. 业务正确性优先于编译成功

代码能够：

编译

不代表：

业务正确。

实现核心业务时必须主动检查：

- 状态转换是否合法
- 是否存在重复执行问题
- 是否存在并发问题
- 是否存在库存错误
- 是否存在重复订单
- 是否存在重复 Ticket
- 是否违反唯一约束
- 是否需要数据库兜底约束
- 是否需要幂等
- 是否需要补偿
- 异常路径是否完整

不要只验证 Happy Path。

---

# 18. 完成任务前的验证

声明任务完成之前，必须根据修改范围进行验证。

至少检查：

1. 是否违反 Maven 模块依赖规则。
2. 是否错误地把业务代码放进 common。
3. 是否跨模块访问了内部实现。
4. 状态转换是否合法。
5. 关键操作是否具备必要幂等性。
6. 数据库约束是否仍然成立。
7. 是否需要增加或修改测试。
8. 是否运行了与修改范围匹配的测试。

Java、Spring、Maven、数据库或核心配置发生重要修改后，应尽可能执行：

`./mvnw verify`

但不需要因为只修改 README 等纯文档文件而无意义执行完整 Maven 验证。

如果因为：

- MySQL 未启动
- Redis 未启动
- RabbitMQ 未启动
- Docker 环境不可用
- 外部依赖不可用
- 其他环境原因

导致完整验证无法执行：

必须明确说明：

- 已执行哪些验证
- 哪些验证没有执行
- 为什么没有执行

不要隐瞒未验证状态。

---

# 19. 项目知识文档

EventHub 的详细长期项目知识维护在：

`docs/`

根 `AGENTS.md` 不承担完整系统说明书职责。

详细业务和架构知识不要大量复制进本文件。

需要时读取对应文档。

主要目录职责：

`docs/specification/`
→ 原始需求和项目说明书

`docs/architecture/`
→ 系统架构、模块关系、核心业务流程和一致性设计

`docs/database/`
→ 数据库表、关系、索引和约束说明

`docs/middleware/`
→ Redis 和 RabbitMQ 等中间件设计

`docs/development/`
→ 当前开发阶段、进度和后续任务

`docs/benchmark/`
→ 性能测试计划和真实测试结果

`docs/decisions/`
→ 重要架构决策及其原因

---

# 20. Living Documentation 规则

EventHub 不仅要求代码能够运行，还要求项目最终能够解释：

- 为什么这样设计
- 为什么使用这些技术
- 每种技术解决了什么问题
- 架构是如何演进的

因此不要等项目完成后，再完全依靠最终代码反推整个设计过程。

开发过程中发生以下重要变化时，应同步维护对应文档：

- 核心业务流程变化
- Maven 模块职责变化
- Maven 模块依赖变化
- 数据库表变化
- 数据库重要索引变化
- 数据库唯一约束变化
- Redis Key 设计变化
- Redis Lua 逻辑变化
- 缓存策略变化
- RabbitMQ Exchange / Queue 变化
- 消息可靠性方案变化
- 幂等方案变化
- 补偿方案变化
- 对账方案变化
- 关键架构决策变化
- 产生新的真实性能测试结果

文档应重点记录：

1. 当前方案是什么。
2. 为什么采用这个方案。
3. 它解决了什么问题。
4. 它还有什么限制。
5. 相比前一个版本发生了什么演进。

不要因为普通的小范围代码修改频繁修改架构文档。

---

# 21. Subagent Dispatch

1. Main Codex 是默认且唯一的 Orchestrator 与 Repository 修改主体；Subagent 默认不得自行级联派发其他 Subagent。
2. 仅当独立 Context 能明显降低理解、审查或验证风险时才调用 Subagent；不得机械执行固定的 Explorer → Reviewer → Test Reviewer 流水线。
3. Existing Implementation Context 不足、调用链不清楚或问题范围未知时，按需调用 explorer。
4. 核心业务、并发、幂等、一致性、状态机、事务、安全、基础设施可靠性或重要跨模块变更完成后，按风险调用 reviewer。
5. 当需要证明核心业务性质已经成立，或核心开发阶段准备结束时，按需调用 test-reviewer。
6. Subagent V1 默认 Read Only；Production Code、Test、Config、Migration、Documentation 等 Repository 修改以及测试执行统一由 Main Codex 负责。
7. Subagent 以 Logical Change Batch 为主要调用粒度，不按单文件、单次保存或每个小型 Fix 反复派发。
8. 默认不并行 Fan-out 多个 Subagent；需要多个角色时，优先按实际依赖顺序逐步调用。
9. 详细 Dispatch 判断标准与流程见 docs/development/agent-dispatch-policy.md。

---

# 22. 项目进度

当前项目开发状态维护在：

`docs/development/progress.md`

完成一个明显的开发阶段后，应更新该文件。

progress.md 主要记录：

- 已完成内容
- 当前阶段
- 当前正在进行的任务
- 已知问题
- 下一步计划

不要把 progress.md 写成完整系统说明书。

它只负责回答：

“EventHub 当前做到哪里了？”

---

# 23. 架构决策记录

如果改变以下重要设计，应同步记录到：

`docs/decisions/`

或者相关 architecture 文档：

- 模块边界
- 核心数据模型
- 库存模型
- 一致性方案
- Redis 数据模型
- RabbitMQ 可靠性模型
- 订单状态机
- 关键技术选型
- 性能优化策略

架构决策应尽可能记录：

问题
→ 备选方案
→ 最终选择
→ 选择原因
→ 代价

不要让重要架构决定只存在于一次 Codex 对话中。

---

# 24. 最终系统说明书

EventHub V1 完成以后，应基于：

- 最终源代码
- 测试
- Flyway Migration
- Benchmark
- Architecture Docs
- Decisions
- Development History

生成完整学习型系统说明书：

`docs/system-manual/`

系统说明书需要帮助 Java 后端开发者从头理解 EventHub。

最终至少应该覆盖：

- 项目定位
- 核心业务模型
- 系统总体架构
- Maven 模块设计
- 数据库设计
- NORMAL 抢票
- FLASH 抢票
- Redis 设计
- RabbitMQ 设计
- 订单状态机
- 数据最终一致性
- 幂等
- 补偿
- 对账
- 缓存治理
- 限流
- 并发设计
- 线程池
- 测试体系
- 性能测试
- 故障场景
- 架构演进
- 关键源码阅读路径
- 项目面试讲解

系统说明书必须以真实实现为准。

禁止：

- 把未实现功能描述为已实现
- 伪造性能数据
- 描述不存在的代码
- 为了让项目看起来复杂而夸大架构

---

# 25. README 与 System Manual 的区别

README 负责：

快速理解项目。

内容应相对精炼，包括：

- 项目介绍
- 核心能力
- 技术栈
- 架构概要
- 启动方式
- 核心演进
- Benchmark 摘要
- 文档入口

`docs/system-manual/`

负责：

深入学习项目。

不要把完整学习手册全部塞进 README。

---

# 26. Git 操作规则

默认允许：

- 查看 Git 状态
- 查看 diff
- 查看历史
- 分析修改

默认不要主动执行：

- 创建 branch
- commit
- push
- merge
- rebase
- reset
- force push
- 删除 branch

只有用户明确要求时，才能执行相应 Git 修改操作。

任何可能造成历史丢失或代码不可恢复的 Git 操作，都必须先得到用户明确确认。

不要擅自提交代码。

---

# 27. 不要自动扩大任务范围

如果任务是：

“实现用户登录”

不要顺手：

- 重构 Event 模块
- 修改库存架构
- 改造 RabbitMQ
- 创建新的抽象层

如果发现其他问题：

可以指出。

但除非它会阻止当前任务正确完成，否则不要未经确认顺手解决。

保持：

Scope Discipline

---

# 28. Codex 输出要求

完成任务后，不要只回答：

“已经完成。”

最终结果应简洁说明：

1. 完成了什么。
2. 修改了哪些核心文件。
3. 是否存在重要设计决定。
4. 执行了哪些测试或验证。
5. 哪些内容没有验证。
6. 是否存在已知风险。
7. 是否需要更新文档。
8. 下一步建议是什么。

如果任务失败或只完成部分：

必须明确说明。

不要把部分完成描述成全部完成。

---

# 29. 核心工程原则

在整个 EventHub 开发过程中始终牢记：

简单方案优先于无意义复杂方案。

业务正确性优先于技术炫技。

数据库约束是重要的最终安全线。

幂等优先于依赖“请求一定只执行一次”。

最终一致性依赖可验证的工程机制，而不是理论假设。

Redis 用于提高高并发执行能力，但 MySQL 保持最终事实。

RabbitMQ 用于异步和削峰，但消费者必须考虑重复消息。

性能优化必须以真实测试为依据。

文档必须跟随真实实现演进。

项目最终目标不是：

“使用了很多技术。”

而是：

“能够清楚解释为什么每一个关键技术存在，以及它解决了什么真实问题。”
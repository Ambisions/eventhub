---
name: database-development
description: >
  用于 EventHub 中涉及 MySQL、Flyway、Schema、约束、索引、
  MyBatis-Plus、MyBatis XML、核心 SQL、条件 UPDATE、
  数据库事务和 SQL 性能分析的任务。
  当功能需要修改数据库结构、实现核心 SQL、设计数据库并发控制、
  幂等约束或查询性能时使用。
---

# EventHub Database Development

本 Skill 定义 EventHub 数据库任务的标准执行流程。

本 Skill 负责：

- MySQL Schema
- Flyway
- Constraint / Index
- MyBatis-Plus
- MyBatis XML
- Core SQL
- Conditional UPDATE
- Database Transaction
- EXPLAIN / SQL Performance

本 Skill 不替代：

- `AGENTS.md`
- `eventhub-development`
- 业务模块规则
- Redis / RabbitMQ 专业 Skill

实际数据库事实以：

当前 Flyway Migration
+
当前代码
+
适用 AGENTS.md
+
项目文档

为准。

---

# 1. 首先检查真实数据库现状

开始数据库任务前，先定位真正相关的：

- Flyway Migration
- Table
- Entity / Domain Object
- Mapper
- Repository
- Mapper XML
- Existing Constraint
- Existing Index
- Related Test

不要根据项目说明书直接假设 Schema 已经实现。

不要因为第一次没有找到目标结构就创建第二套平行实现。

---

# 2. 识别数据库任务类型

先判断当前任务主要属于哪一种。

## Schema / Migration

例如：

- 新建表
- 新增字段
- 修改字段
- Unique Constraint
- Index
- 数据类型
- Flyway

读取：

`references/schema-and-flyway.md`

## Database Concurrency / Idempotency

例如：

- NORMAL 库存竞争
- Conditional UPDATE
- affectedRows
- Order 状态竞争
- Transaction
- Duplicate Key
- SELECT FOR UPDATE
- 数据库幂等安全线

读取：

`references/mysql-concurrency.md`

## Query / Index / Performance

例如：

- 查询变慢
- 联合索引
- EXPLAIN
- N+1
- 分页
- Slow SQL
- SQL Optimization

读取：

`references/index-and-explain.md`

一个任务可能需要多份 Reference。

只读取当前任务真正涉及的内容。

---

# 3. Flyway 是正式 Schema 入口

任何正式 Schema 变化都必须通过：

`eventhub-app/src/main/resources/db/migration/`

中的新 Flyway Migration 实施。

不要只修改：

Java Entity
或
Mapper

却遗漏真实数据库。

也不要通过 Navicat 修改正式 Schema 后不留下 Migration。

已经稳定使用的历史 Migration 原则上不要随意重写。

详细规则见：

`references/schema-and-flyway.md`

---

# 4. 先设计业务不变量，再设计表

创建或修改 Schema 前先问：

数据库需要兜底哪些业务事实？

根据真实需求考虑：

- PRIMARY KEY
- UNIQUE
- NOT NULL
- DEFAULT
- CHECK
- Index

对于：

- 唯一业务结果
- 幂等
- 重复消费
- 并发写入

不要只依赖 Java `if`。

数据库 Constraint 是 EventHub 的最后一道业务安全线之一。

---

# 5. MyBatis-Plus 与 XML 的选择

普通简单 CRUD：

优先使用 MyBatis-Plus。

例如：

- 主键查询
- 普通 INSERT
- 简单 UPDATE
- 简单单表查询

以下场景优先显式 Mapper / XML：

- 核心 Conditional UPDATE
- 并发竞争 SQL
- 复杂 JOIN
- 聚合查询
- 复杂动态 SQL
- 需要精确控制 SQL 形态
- 需要重点 Review / EXPLAIN 的 SQL

不要为了展示技术把所有 CRUD 都写成 XML。

也不要把决定并发正确性的核心 SQL 隐藏在难以理解的 Wrapper 中。

---

# 6. Repository / Mapper 边界

Repository 表达模块的数据访问需求。

Mapper 负责具体 MyBatis 持久化。

跨模块不得直接访问其他模块：

- Mapper
- Repository

继续遵守模块 `AGENTS.md`。

不要为了形式创建大量没有价值的一行包装，
但核心 SQL 必须具有明确归属。

---

# 7. 并发数据库操作优先原子表达

遇到数据库并发问题时，优先考虑：

- Conditional UPDATE
- affectedRows
- Unique Constraint
- 状态条件
- Atomic SQL

不要首先想到：

- synchronized
- Distributed Lock
- SELECT FOR UPDATE

例如 NORMAL 库存和 Order 状态竞争的具体规则见：

`references/mysql-concurrency.md`

---

# 8. 数据库事务以业务原子性为依据

如果多个 MySQL 修改必须：

全部成功
或
全部失败

则建立合理的 Spring Local Transaction。

事务范围保持：

短
+
明确。

不要在事务中加入无意义的：

- 长时间计算
- 外部网络请求
- 等待
- 与事务无关操作

MySQL Transaction 不能解决：

Redis + RabbitMQ + MySQL

整体原子性。

---

# 9. 索引来自真实查询路径

不要先创建大量 Index 再寻找用途。

设计索引前分析实际：

- WHERE
- JOIN
- ORDER BY
- GROUP BY
- 查询频率
- 数据规模
- 数据选择性

性能问题优先根据：

EXPLAIN
+
Slow Query
+
Benchmark
+
真实指标

分析。

详细规则见：

`references/index-and-explain.md`

---

# 10. 数据库修改后的验证

根据修改范围验证：

## Schema

- Migration 可执行
- Table / Column 正确
- Constraint 存在
- Index 存在
- Java Mapping 一致

## Core SQL

- 正常结果
- 边界情况
- affectedRows
- Duplicate Data
- Transaction Rollback
- 必要并发竞争

## Query Performance

根据需要执行：

EXPLAIN

性能相关结论必须来自真实证据。

---

# 11. 文档维护

数据库事实变化时检查：

`docs/database/schema.md`

`docs/database/indexes.md`

以及相关架构文档。

Flyway 表达：

数据库实际发生了什么变化。

docs 解释：

为什么这样设计。

不要用 docs 代替 Migration，
也不要用 Migration 代替设计说明。
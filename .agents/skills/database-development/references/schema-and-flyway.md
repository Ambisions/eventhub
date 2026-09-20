# EventHub Schema 与 Flyway 参考规则

仅当任务涉及：

- Schema
- Migration
- Table / Column
- Constraint
- Foreign Key
- 审计字段
- 数据类型

时读取。

---

# 1. Flyway

正式 Schema 变化必须通过：

`eventhub-app/src/main/resources/db/migration/`

中的新 Migration 实施。

流程：

1. 查看已有 Migration。
2. 确认当前 Schema。
3. 创建下一个合法 Migration。
4. 修改 Schema。
5. 同步 Java Mapping。
6. 同步 Mapper / XML。
7. 更新 Test。
8. 必要时更新 docs。

稳定历史 Migration 原则上不要重写。

Navicat 可以用于：

- 查看 Schema
- 查看数据
- SQL 调试
- EXPLAIN

但不得成为正式 Schema Source of Truth。

---

# 2. Constraint

根据真实业务积极使用：

- PRIMARY KEY
- UNIQUE
- NOT NULL
- 合理 DEFAULT
- 合理 CHECK

不要只依赖应用层判断保证数据库合法。

EventHub 重要业务事实通常采用：

Application Rule
+
Database Constraint

多层保护。

---

# 3. 核心 Unique Constraint

修改相关表时必须检查已有业务安全线。

## event_inventory

`UNIQUE(session_id)`

一个 Session 只有一个 Inventory。

## ticket_order

`UNIQUE(order_no)`

`UNIQUE(user_id, session_id, purchase_guard)`

Reservation concept 为未来冻结方向，但精确持久化模型、`reservation_id` 与
唯一安全线尚未冻结。

## ticket

`UNIQUE(ticket_no)`

`UNIQUE(order_id)`

`UNIQUE(user_id, session_id)`

## WaitingList persistence

WaitingList 为未来 Planned capability；Schema 与唯一约束尚未冻结。

修改这些约束必须先说明：

- 为什么现有约束不再适用
- 新方案如何继续保证原业务不变量

---

# 4. Foreign Key

EventHub V1 不默认大量建立物理 Foreign Key。

模块数据关系主要通过：

- 明确业务字段
- Index
- Unique Constraint
- Application / Domain Rule
- Local Transaction

维护。

目的不是因为：

“Foreign Key 一定性能差”。

而是为了减少：

- Schema 强耦合
- 测试数据构造复杂度
- 补偿和修复复杂度

如果某个 Foreign Key 有明确一致性收益，
可以针对该关系单独评估。

不要形成：

“EventHub 禁止 Foreign Key”

的绝对规则。

---

# 5. 审计字段

核心业务表默认考虑：

`created_at`

`updated_at`

但不要机械加入：

- created_by
- updated_by
- deleted
- version
- tenant_id

只有真实业务需求存在时才增加。

尤其不要因为项目涉及高并发，
就给所有表添加：

`version`

乐观锁字段。

---

# 6. 数据类型

字段必须对应明确业务语义。

注意：

- 主键类型统一
- 金额不要使用浮点数
- 时间字段语义统一
- 状态字段清晰
- Boolean 语义明确
- String 长度合理

不要为了“以后可能用到”增加大量无业务定义字段。

---

# 7. 业务状态优先于通用 deleted

不要默认所有业务表都添加：

`deleted`

Order、Ticket、WaitingList 等拥有明确生命周期的对象：

优先使用业务状态表达生命周期。

不要用：

deleted = 1

替代真正状态机。

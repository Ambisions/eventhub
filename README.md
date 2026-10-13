# EventHub

[简体中文](README.md) | [English](README_EN.md)

> 基于 Java 17 与 Spring Boot 的高并发活动预约与票务平台

![Java 17](https://img.shields.io/badge/Java-17-007396?logo=openjdk&logoColor=white)
![Spring Boot 3.5.16](https://img.shields.io/badge/Spring%20Boot-3.5.16-6DB33F?logo=springboot&logoColor=white)
![MySQL 8.4](https://img.shields.io/badge/MySQL-8.4-4479A1?logo=mysql&logoColor=white)
![Redis 8.2](https://img.shields.io/badge/Redis-8.2-DC382D?logo=redis&logoColor=white)
![RabbitMQ 4.3](https://img.shields.io/badge/RabbitMQ-4.3-FF6600?logo=rabbitmq&logoColor=white)
![License Apache-2.0](https://img.shields.io/badge/License-Apache--2.0-D22128)

EventHub 是一个运行于单 JVM 的 Java 模块化单体，围绕库存正确性、订单生命周期、FLASH 抢票入口控制和异步通知设计。MySQL 保存权威业务状态，Redis Lua 负责 FLASH admission，RabbitMQ 提供支付提交后的 best-effort 通知基础。高并发是架构目标，并非未经验证的性能结论。

**核心亮点**

- MySQL 条件更新与数据库约束共同防止超卖、破坏库存守恒和重复有效购买。
- NORMAL 与 FLASH 两条预约链路共享权威 MySQL 订单和库存模型。
- Order、Inventory 与 Ticket 在支付时加入同一个本地事务。
- RabbitMQ 通知投影具备消费幂等，但明确保留 best-effort 交付边界。

**快速导航**：[架构](docs/architecture/overview.md) · [核心流程](docs/architecture/core-flows.md) · [本地运行](docs/deployment/local-development.md) · [HTTP API](docs/api/http-api.md)

## 1. 项目概览与目标

EventHub 面向技术大会、沙龙、展览和演出等预约场景，重点展示业务正确性如何从简单数据库事务逐步演进到 Redis 和 RabbitMQ，而不是堆砌中间件。

- **架构形态**：Java Modular Monolith；`eventhub-app` 是唯一 Composition Root，所有模块运行于同一 JVM。
- **模块协作**：Controller 与默认跨领域编排位于 `eventhub-app`；Reserve、Pay、Cancel、Timeout、Refund 是由 `eventhub-order` 持有事务编排的冻结窄例外，参与模块仍仅通过公开 `api` package 加入流程。
- **数据职责**：MySQL 是 Source of Truth；Redis 是 FLASH admission/execution state；RabbitMQ 是通知传输层。
- **工程目标**：业务正确性、数据一致性、幂等、失败边界、可验证性和可解释的架构演进。

全局角色只有 `USER` 和 `ADMIN`。Organizer 表示 Event 资源所有者，不是第三种全局角色。

## 2. 功能与当前状态

| 状态 | 能力 |
| --- | --- |
| **IMPLEMENTED** | 注册/登录、JWT 认证、Event/Session 管理、Organizer ownership、库存配置、NORMAL/FLASH 预约、`WAIT_PAY` 生命周期、支付/取消/超时、退款、Ticket 签发/查询/核销 |
| **FOUNDATION IMPLEMENTED** | `TicketIssuedEvent`、提交后 best-effort RabbitMQ 发布、durable direct topology、Consumer 本地事务、`user_notification` 持久化、重复消息幂等 |
| **PLANNED / DEFERRED** | Transactional Outbox、Publisher Confirm/Return、Retry/DLQ、自动对账、Benchmark、CI |

当前边界：

- Notification 不保证交付，尚无 Outbox、Publisher Confirm/Return、Retry/DLQ 或通知查询 API。
- FLASH 执行 `Redis Lua admission → 同步 MySQL reservation`；没有 Redis queue、异步订单创建或 Redis/MySQL 原子提交。
- FLASH 的提交后 Redis 同步属于 best-effort，尚无自动对账和 Redis crash recovery。

## 3. 架构

```text
Client
  → eventhub-app (HTTP, Security, Application Services, Composition Root)
    → in-process public APIs
      → user / auth / event / inventory / order / ticket / notification modules
        → MySQL (authoritative state)
        → Redis (FLASH admission and execution state)
        → RabbitMQ (best-effort notification transport)
```

这是单部署单元、单 JVM 的模块化单体，不是微服务系统。模块边界、事务归属和基础设施职责详见[架构概览](docs/architecture/overview.md)。

## 4. 核心业务流程

| 流程 | 当前实现 | 关键边界 |
| --- | --- | --- |
| **NORMAL** | MySQL 条件扣减库存，并在本地事务内创建 `WAIT_PAY` Order | 热门库存行可能产生数据库竞争 |
| **FLASH** | Redis Lua admission 后同步执行 MySQL Reservation，再 best-effort settle Redis | Redis Lua 与 MySQL commit 不是原子分布式事务 |
| **Payment** | Order `WAIT_PAY → PAID`、Inventory `locked → sold`、Ticket 签发在同一 MySQL 事务中完成 | 原子性边界限定在单数据库本地事务 |
| **Notification** | Pay 提交后 best-effort 发布 `TicketIssuedEvent`，Consumer 独立事务写入通知投影 | Broker/JVM 失败窗口下不保证通知交付 |

完整时序、失败分类和状态机见[核心流程](docs/architecture/core-flows.md)。

## 5. 工程亮点

### 5.1 MySQL 条件库存更新

- **问题**：并发请求可能同时看到剩余库存并发生超卖。
- **决策**：使用带 `available_stock > 0` 条件的原子 `UPDATE`，以 affected rows 判定成功。
- **保证**：同一库存行上只有实际扣减成功的请求进入后续流程。
- **取舍**：高热 Session 仍可能在 MySQL 行锁上竞争。参见 [InventoryMapper.xml](eventhub-inventory/src/main/resources/mapper/inventory/InventoryMapper.xml) 和 [NormalReservationConcurrencyIntegrationTest](eventhub-app/src/test/java/com/eventhub/order/NormalReservationConcurrencyIntegrationTest.java)。

### 5.2 库存守恒

- **问题**：多条生命周期路径可能造成库存分量为负或总量漂移。
- **决策**：Flyway CHECK 约束要求各库存分量非负且 `available + locked + sold = total`。
- **保证**：数据库拒绝破坏库存守恒的不合法写入。
- **取舍**：约束保护 MySQL 权威状态，但不能消除 Redis/MySQL 的短暂同步窗口。参见[数据库 Schema](docs/database/schema.md)与[索引和约束](docs/database/indexes.md)。

### 5.3 单用户单场次购买保护

- **问题**：同一用户可能重复创建有效订单或通过终态转换绕过限制。
- **决策**：生成列 `purchase_guard` 与唯一约束共同覆盖 `WAIT_PAY`、`PAID`、`REFUNDED`。
- **保证**：同一用户和 Session 只能保留一条有效或历史购买记录；`CANCELLED`、`CLOSED` 才释放 guard。
- **取舍**：`REFUNDED` 不允许重新购买同一 Session，这是明确的 V1 业务规则。参见 [OrderMapper.xml](eventhub-order/src/main/resources/mapper/order/OrderMapper.xml) 和 [schema.md](docs/database/schema.md)。

### 5.4 Order / Inventory / Ticket 事务原子性

- **问题**：支付成功但库存未售出或 Ticket 未签发会形成跨领域不一致。
- **决策**：Order 拥有支付事务编排，Inventory 和 Ticket 通过公开 API 加入同一个 MySQL 事务。
- **保证**：三者全部提交或全部回滚。
- **取舍**：该保证依赖模块化单体的单数据库事务边界。参见 [PaymentTicketAtomicityIntegrationTest](eventhub-app/src/test/java/com/eventhub/order/PaymentTicketAtomicityIntegrationTest.java)。

### 5.5 Redis Lua FLASH Admission

- **问题**：热门 FLASH 请求直接竞争数据库会放大入口压力。
- **决策**：使用四个 Lua primitive 原子处理准备、admission、settle 和订单生命周期同步。
- **保证**：每个 Lua 操作内部的库存与用户状态转换是原子的。
- **取舍**：Lua 原子性不能覆盖后续 MySQL commit；模糊失败会保守保留 pending，且当前没有自动对账。参见 [flash_reserve.lua](eventhub-inventory/src/main/resources/lua/flash_reserve.lua) 和 [FlashReservationFailureIntegrationTest](eventhub-app/src/test/java/com/eventhub/flash/FlashReservationFailureIntegrationTest.java)。

### 5.6 RabbitMQ 幂等通知投影

- **问题**：消息可能重复投递，而通知持久化失败不应回滚已经提交的支付。
- **决策**：支付提交后 best-effort 发布；Consumer 使用独立本地事务，并以 `event_id` 和 `(type, business_reference)` 双重唯一约束校验幂等。
- **保证**：等价重复不会创建第二条通知投影，冲突重复会失败。
- **取舍**：当前 `AUTO` acknowledgement/no-requeue 基线没有 Outbox、Confirm/Return 或 Retry/DLQ，因此不保证交付。参见 [UserNotificationService](eventhub-notification/src/main/java/com/eventhub/notification/service/UserNotificationService.java) 和 [UserNotificationConsumerIntegrationTest](eventhub-app/src/test/java/com/eventhub/notification/UserNotificationConsumerIntegrationTest.java)。

## 6. 技术栈

| 层次 | 技术 |
| --- | --- |
| Runtime | Java 17、Spring Boot 3.5.16、Spring MVC |
| Security | Spring Security、JWT |
| Persistence | MyBatis-Plus 3.5.17、MyBatis XML、Flyway |
| Database | MySQL 8.4.11 / InnoDB |
| High-concurrency admission | Redis 8.2.9、Spring Data Redis、Lettuce、Redis Lua |
| Messaging | RabbitMQ 4.3.5-management、Spring AMQP |
| Testing | JUnit 5、Mockito、MockMvc、真实 MySQL/Redis/RabbitMQ 集成测试 |
| Build | Maven Wrapper 3.3.4、Maven 3.9.16 |

## 7. 快速开始

前置要求：Git、JDK 17、Docker Engine、`docker compose`，并确保本地端口 `3306`、`6379`、`5672`、`15672`、`8080` 可用。

> 在执行下面的构建和启动命令前，请先完整遵循[本地开发指南](docs/deployment/local-development.md)：复制 `.env.example` 为 `.env`，安全导入环境变量到运行 Java 的同一个终端，并生成随机 32-byte `EVENTHUB_JWT_SECRET_BASE64`。宿主 Java 进程不会自动读取 `.env`。

启动并等待 MySQL、Redis、RabbitMQ healthy：

```sh
docker compose up -d
docker compose ps
```

PowerShell：

```powershell
.\mvnw.cmd -pl eventhub-app -am package -DskipTests
java -jar .\eventhub-app\target\eventhub-app.jar
```

POSIX：

```sh
sh ./mvnw -pl eventhub-app -am package -DskipTests
java -jar eventhub-app/target/eventhub-app.jar
```

最小 Smoke Path：`Register → Login → GET /api/users/me`。请求示例和响应契约见 [HTTP API](docs/api/http-api.md)。

这些命令已经过源码级核对，但尚未完成首次公开发布的 fresh-clone、跨平台 Runtime Verification。

## 8. 测试与验证

最近冻结的历史本地验证记录（2026-10-12）：

| 范围 | Suites | Tests | Failures | Errors | Skipped | 结果 |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| Full Maven Reactor | 77 | 365 | 0 | 0 | 0 | `BUILD SUCCESS` |

该记录来自本地历史验证，并由[开发进度](docs/development/progress.md)保存；它不是 GitHub CI 状态，也不是本次 Public Release 的 fresh runtime 验证。项目当前没有可公开的 Benchmark 测量结果，不声明 QPS、TPS、P95/P99 或覆盖率数字。

## 9. 文档与路线图

| 文档 | 内容 |
| --- | --- |
| [架构概览](docs/architecture/overview.md) | 模块边界、依赖方向、事务和基础设施职责 |
| [核心流程](docs/architecture/core-flows.md) | NORMAL、FLASH、支付、通知、状态机和失败边界 |
| [本地开发](docs/deployment/local-development.md) | 环境变量、安全密钥、构建、运行和 Smoke Test |
| [HTTP API](docs/api/http-api.md) | 认证、端点、请求响应和错误语义 |
| [数据库 Schema](docs/database/schema.md) / [索引与约束](docs/database/indexes.md) | 表、字段、索引和数据库安全线 |
| [V1 Specification](docs/specification/eventhub-v1.md) | 原始需求、演进路线和规划边界 |
| [Development Progress](docs/development/progress.md) | 已完成阶段、历史验证和后续工作 |

后续方向包括 Transactional Outbox、Publisher Confirm/Return、Retry/DLQ、自动对账、真实性能 Benchmark 和 CI；这些能力均为 **PLANNED / DEFERRED**，没有承诺实施日期。

## 10. 贡献、安全与许可证

- 贡献前请阅读 [CONTRIBUTING.md](CONTRIBUTING.md)。
- 安全报告规则见 [SECURITY.md](SECURITY.md)。GitHub Private Vulnerability Reporting 尚未启用，当前没有私密报告渠道；请勿在公开 Issue 中披露未修复漏洞。
- 项目采用 [Apache License 2.0](LICENSE)。第三方归属信息见 [NOTICE](NOTICE)。

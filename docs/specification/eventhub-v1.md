# EventHub 高并发活动预约与票务平台

## 项目设计与开发说明书 V1.0

---

## 1. 项目概述

### 1.1 项目名称

**EventHub —— 高并发活动预约与票务平台**

### 1.2 项目定位

EventHub 是一个面向技术大会、线下沙龙、展览、演出等场景的活动预约与限量票务平台。

系统同时支持：

- 普通活动报名
- 热门活动高并发抢票
- 活动多场次管理
- 订单支付与超时取消
- 电子票生成与核销
- 退款与库存恢复
- 候补排队
- 消息通知
- 高并发库存控制
- Redis / MySQL 最终一致性
- RabbitMQ 异步削峰
- 幂等、限流、补偿和对账

本项目重点不是实现大量 CRUD，而是围绕：

**高并发、库存、订单、消息可靠性、数据一致性以及性能优化**

构建完整 Java 后端业务闭环。

---

# 2. 项目目标

项目主要用于展示 Java 后端工程能力，覆盖：

### Java 基础

- 集合
- ConcurrentHashMap
- BlockingQueue
- ThreadLocal
- ThreadPoolExecutor
- CAS 思想
- 并发安全

### Spring

- Spring Boot
- Spring MVC
- Spring Security
- Spring Transaction
- Validation
- AOP
- Actuator

### 数据库

- MySQL
- InnoDB
- 事务
- 行锁
- MVCC
- 条件 UPDATE
- 唯一约束
- 联合索引
- EXPLAIN
- 慢 SQL

### Redis

- Cache Aside
- String
- Set
- Hash
- ZSet
- Lua
- 缓存穿透
- 缓存击穿
- 缓存雪崩
- 限流
- 幂等

### RabbitMQ

- 异步
- 削峰
- Publisher Confirm
- Return Callback
- Manual ACK
- Retry
- DLQ
- 延迟消息
- 消费幂等

### JVM / 性能

- JVM Heap
- G1
- GC Log
- Thread Dump
- HikariCP
- JMeter
- Micrometer
- Actuator
- Arthas / VisualVM

---

# 3. 核心业务规则

## 3.1 活动模型

一个 Event 可以拥有多个 EventSession。

```text
Event
  │
  │ 1:N
  ▼
EventSession
  │
  │ 1:1
  ▼
EventInventory

```

真正参与抢票和库存计算的是：

```text
sessionId

```

而不是 eventId。

---

## 3.2 用户购票限制

同一个用户在同一个 Session：

**最终只能成功购买一张票。**

用户产生：

```text
WAIT_PAY
→ CANCELLED

```

或者：

```text
WAIT_PAY
→ TIMEOUT

```

之后允许重新抢票。

但是一旦：

```text
PAID
→ Ticket generated

```

即使以后退款，也不允许再次参与同场次抢票。

---

## 3.3 订单生命周期

```text
                     PAY
WAIT_PAY ─────────────────────→ PAID
   │                              │
   │                              │ REFUND
   ├── CANCEL ─→ CANCELLED        ▼
   │                           REFUNDED
   │
   └── TIMEOUT ─→ TIMEOUT

```

其中：

```text
CANCELLED
TIMEOUT
REFUNDED

```

为终态。

---

## 3.4 库存模型

库存包含：

```text
totalStock
availableStock
lockedStock
soldStock

```

满足：

```text
totalStock
=
availableStock
+
lockedStock
+
soldStock

```

抢票成功：

```text
available -1
locked +1

```

支付：

```text
locked -1
sold +1

```

取消或超时：

```text
locked -1
available +1

```

退款：

```text
sold -1
available +1

```

---

## 3.5 待支付规则

抢票成功：

```text
锁库存
↓
创建 WAIT_PAY
↓
15分钟支付窗口

```

超过 15 分钟：

```text
WAIT_PAY
↓
TIMEOUT
↓
释放库存

```

---

## 3.6 候补规则

售罄后用户可以进入 WaitingList。

库存释放时：

```text
WaitingList 第一名
↓
获得购买资格
↓
10分钟有效期

```

系统不自动为候补用户创建订单。

资格过期：

```text
EXPIRED
↓
下一位候补用户获得资格

```

---

# 4. 技术栈

## 后端

```text
Java 17
Spring Boot 3.5.x
Spring MVC
Spring Security
JWT
Jakarta Validation
Spring Transaction

```

## 数据访问

```text
MyBatis-Plus
+
MyBatis XML

```

普通 CRUD：

```text
MyBatis-Plus

```

核心 SQL：

```text
手写 Mapper/XML

```

---

## 数据库

```text
MySQL 8.4 LTS
InnoDB
HikariCP

```

---

## Redis

```text
Redis
Spring Data Redis
Lettuce
Lua

```

V1 不把 Redisson 分布式锁作为核心方案。

---

## 消息队列

```text
RabbitMQ
Spring AMQP

```

---

## 测试与监控

```text
JUnit 5
Mockito
MockMvc
JMeter
Spring Boot Actuator
Micrometer
VisualVM
Arthas

```

---

## 本地基础设施

```text
Docker Compose

├── MySQL
├── Redis
└── RabbitMQ

```

---

# 5. 系统架构

EventHub 当前采用：

**Maven 多模块 + 模块化单体**

不是微服务。

最终只有一个：

```text
eventhub-app.jar

```

运行在一个 JVM 中。

---

# 6. Maven 项目结构

```text
eventhub
│
├── pom.xml
│
├── docker-compose.yml
│
├── README.md
│
├── docs
│   ├── architecture.md
│   ├── database.md
│   ├── redis.md
│   ├── rabbitmq.md
│   └── benchmark.md
│
├── eventhub-common
│
├── eventhub-user
│
├── eventhub-auth
│
├── eventhub-event
│
├── eventhub-inventory
│
├── eventhub-order
│
├── eventhub-ticket
│
├── eventhub-notification
│
└── eventhub-app

```

---

# 7. 模块职责

## eventhub-common

存放真正公共的基础能力：

```text
common
├── exception
├── result
├── constants
├── enums
├── utils
└── context

```

禁止将具体业务 Service 放入 common。

---

## eventhub-user

```text
User
用户资料
用户状态
用户查询

```

---

## eventhub-auth

```text
登录
JWT
Spring Security
角色鉴权
JWT Blacklist
CurrentUser

```

---

## eventhub-event

```text
Event
EventSession
活动发布
活动查询
活动审核
场次管理

```

---

## eventhub-inventory

```text
Inventory
库存初始化
库存锁定
库存释放
Redis库存
Lua
WaitingList

```

这是系统核心模块之一。

---

## eventhub-order

```text
订单创建
支付
取消
退款
超时
订单状态机
Reservation
Outbox

```

这是系统另外一个核心模块。

---

## eventhub-ticket

```text
Ticket创建
Ticket查询
Ticket核销
Ticket失效

```

---

## eventhub-notification

```text
支付成功通知
候补成功通知
订单通知
活动提醒

```

V1 可以使用日志模拟真实短信/邮件。

---

## eventhub-app

系统入口和业务编排层：

```text
eventhub-app
├── application
├── controller
├── config
├── handler
├── filter
└── EventHubApplication

```

Controller 统一放在 app。

跨领域流程由：

```text
ApplicationService

```

编排。

例如：

```text
ReservationApplicationService
        │
        ├── EventService
        ├── InventoryService
        └── OrderService

```

---

# 8. 模块依赖原则

必须遵守：

1. 模块依赖保持单向。
2. 禁止循环 Maven 依赖。
3. 禁止跨模块调用其他模块 Mapper。
4. 跨模块只能通过公开 Service/API。
5. Repository / Mapper 只属于自己的领域。
6. Application Service 负责跨领域编排。
7. Domain Service 负责本领域业务规则。

---

# 9. 核心数据库表

V1 核心业务表：

```text
user
event
event_session
event_inventory
ticket_order
ticket
waiting_list

```

高并发可靠性增加：

```text
reservation_request
mq_outbox

```

共：

**9 张核心表。**

---

# 10. 关键数据库约束

## event\_inventory

```text
UNIQUE(session_id)

```

保证：

```text
Session : Inventory = 1:1

```

---

## ticket\_order

```text
UNIQUE(order_no)

UNIQUE(reservation_id)

```

索引：

```text
(user_id, created_at)

(session_id, status)

(status, expire_time)

```

---

## ticket

```text
UNIQUE(ticket_code)

UNIQUE(order_id)

UNIQUE(user_id, session_id)

```

最终保证：

> 一个用户一个场次最多成功获得一张票。

---

## waiting\_list

```text
UNIQUE(user_id, session_id)

INDEX(session_id, status, joined_at)

INDEX(status, qualification_expire_time)

```

---

# 11. NORMAL 普通抢票流程

```text
POST Reservation
      ↓
检查Session
      ↓
检查售票时间
      ↓
检查是否购买过
      ↓
MySQL条件UPDATE库存
      ↓
INSERT WAIT_PAY Order
      ↓
COMMIT

```

库存 SQL：

```text
UPDATE event_inventory

available_stock = available_stock - 1
locked_stock = locked_stock + 1

WHERE
session_id = ?
AND available_stock > 0

```

通过：

```text
affectedRows

```

判断是否抢票成功。

这里不需要：

```text
synchronized
Redis Lock
SELECT FOR UPDATE

```

---

# 12. FLASH 高并发抢票流程

最终流程：

```text
User
 ↓
JWT
 ↓
用户限流
 ↓
Session限流
 ↓
Redis Lua
 ↓
Reservation
 ↓
Outbox
 ↓
RabbitMQ
 ↓
Order Consumer
 ↓
MySQL

```

接口首先返回：

```text
PROCESSING
+
reservationId

```

然后客户端：

```text
GET /reservations/{reservationId}

```

查询最终结果。

---

# 13. Redis 数据设计

## 普通缓存

```text
eventhub:event:{id}:detail

eventhub:session:{id}:detail

```

---

## 抢票库存

```text
eventhub:session:{id}:stock

```

---

## 当前占用用户

```text
eventhub:session:{id}:occupied-users

```

---

## 历史购买用户

```text
eventhub:session:{id}:purchased-users

```

---

## 用户当前 Reservation

```text
eventhub:session:{id}:reservation-map

```

Hash：

```text
userId → reservationId

```

---

## 候补

```text
eventhub:waiting:{sessionId}:queue

```

ZSet。

---

# 14. 抢票 Lua 职责

一次原子执行：

```text
检查是否已经购买
↓
检查是否已有Reservation
↓
检查售票时间
↓
检查库存
↓
stock - 1
↓
加入 occupied-users
↓
保存 reservationId
↓
创建临时Reservation状态

```

不能使用：

```text
GET
↓
Java判断
↓
DECR

```

这种非原子组合。

---

# 15. RabbitMQ 架构

核心队列：

```text
order.create.queue
order.create.dlq

order.delay.queue
order.timeout.queue

notification.queue

```

订单创建：

```text
Redis
↓
Outbox
↓
RabbitMQ
↓
Order Consumer
↓
MySQL

```

---

# 16. MQ 可靠性

Producer：

```text
Transactional Outbox
Publisher Confirm
Return Callback
Durable Exchange
Durable Queue
Persistent Message

```

Consumer：

```text
Manual ACK
Retry
DLQ
reservation_id UNIQUE

```

采用：

> At-Least-Once + Idempotent Consumer

而不是追求理论上的 Exactly Once。

---

# 17. 最终一致性

核心原则：

```text
MySQL
= Source of Truth

Redis
= 高并发执行层

RabbitMQ
= 异步传输层

```

跨组件通过：

```text
Local Transaction
+
Outbox
+
Retry
+
Idempotency
+
Compensation
+
Reconciliation

```

保证最终一致。

---

# 18. 缓存策略

活动详情：

```text
Cache Aside

```

查询：

```text
Redis
↓ MISS
MySQL
↓
Redis

```

更新：

```text
UPDATE MySQL
↓
COMMIT
↓
DELETE Cache

```

---

## 缓存穿透

```text
参数校验
+
Null Cache

```

---

## 缓存击穿

```text
热点预热
+
随机 TTL
+
SET NX 重建互斥

```

---

## 缓存雪崩

```text
TTL 打散
+
预热
+
不同数据不同 TTL
+
限流/降级

```

---

# 19. Redis 故障策略

NORMAL：

```text
Redis故障
↓
有限降级到MySQL

```

FLASH：

```text
Redis故障
↓
Fail Fast

```

禁止热门抢票接口突然让全部流量进入 MySQL。

---

# 20. 限流

FLASH 请求：

```text
JWT
 ↓
User Rate Limit
 ↓
Session Rate Limit
 ↓
Redis Lua

```

用户限流：

```text
userId + sessionId

```

Session 限流：

```text
sessionId

```

V1 使用：

```text
Redis Lua Fixed Window

```

具体阈值必须通过压测决定。

---

# 21. 幂等设计

不同业务使用不同幂等方案。

### 抢票

```text
userId + sessionId
→ reservationId

```

重复请求返回原 reservationId。

### 支付

```text
WHERE status = WAIT_PAY

```

### 取消

```text
WAIT_PAY → CANCELLED

```

### 退款

```text
PAID → REFUNDED

```

### MQ

```text
UNIQUE(reservation_id)

```

### 库存释放

```text
Reservation状态
+
Lua

```

不使用“万能分布式锁”解决所有问题。

---

# 22. 线程池设计

RabbitMQ Consumer：

```text
使用 Listener Container 自身并发

```

不额外套线程池。

自定义线程池：

```text
reconciliationExecutor
warmupExecutor
notificationExecutor（可选）

```

要求：

```text
显式 corePoolSize
显式 maxPoolSize
有界 queue
显式 rejection policy
ThreadNamePrefix
异常处理
MDC 清理
优雅关闭

```

禁止核心代码直接：

```text
Executors.newFixedThreadPool()

```

---

# 23. 主要 REST API

## Auth

```http
POST /api/auth/register

POST /api/auth/login

POST /api/auth/logout

```

---

## User

```http
GET /api/users/me

PUT /api/users/me

```

---

# 24. Event API

公开接口：

```http
GET /api/events

GET /api/events/{eventId}

GET /api/events/{eventId}/sessions

GET /api/sessions/{sessionId}

```

Organizer：

```http
POST /api/organizer/events

PUT /api/organizer/events/{eventId}

POST /api/organizer/events/{eventId}/sessions

PUT /api/organizer/sessions/{sessionId}

POST /api/organizer/events/{eventId}/submit

```

Admin：

```http
POST /api/admin/events/{eventId}/approve

POST /api/admin/events/{eventId}/reject

```

---

# 25. Reservation API

核心接口：

```http
POST /api/sessions/{sessionId}/reservations

```

NORMAL：

可能直接返回：

```text
orderNo

```

FLASH：

返回：

```text
reservationId
PROCESSING

```

查询：

```http
GET /api/reservations/{reservationId}

```

返回：

```text
PROCESSING

SUCCESS
+ orderNo

FAILED

```

---

# 26. Order API

```http
GET /api/orders

GET /api/orders/{orderNo}

POST /api/orders/{orderNo}/pay

POST /api/orders/{orderNo}/cancel

POST /api/orders/{orderNo}/refund

```

---

# 27. Ticket API

```http
GET /api/tickets

GET /api/tickets/{ticketCode}

POST /api/tickets/{ticketCode}/verify

```

`verify` 主要由 Organizer/Admin 使用。

---

# 28. WaitingList API

```http
POST /api/sessions/{sessionId}/waiting-list

DELETE /api/sessions/{sessionId}/waiting-list

GET /api/sessions/{sessionId}/waiting-list/me

```

---

# 29. 核心 Application Services

建议在：

```text
eventhub-app/application

```

建立：

```text
ReservationApplicationService

PaymentApplicationService

CancellationApplicationService

RefundApplicationService

WaitingListApplicationService

EventPublishApplicationService

```

不要构造一个：

```text
EventHubService

```

负责整个系统。

---

# 30. 第一阶段代码目录建议

以 Order 为例：

```text
eventhub-order
└── src/main/java/com/eventhub/order
    │
    ├── domain
    │   ├── TicketOrder.java
    │   ├── OrderStatus.java
    │   └── ReservationRequest.java
    │
    ├── service
    │   ├── OrderService.java
    │   └── ReservationService.java
    │
    ├── repository
    │   ├── OrderRepository.java
    │   └── ReservationRepository.java
    │
    ├── mapper
    │   ├── OrderMapper.java
    │   └── ReservationMapper.java
    │
    ├── message
    │   ├── CreateOrderMessage.java
    │   └── OrderTimeoutMessage.java
    │
    └── consumer
        ├── CreateOrderConsumer.java
        └── OrderTimeoutConsumer.java

```

不要一开始追求完整 DDD。

我们的目标是：

```text
边界清楚
+
代码容易读
+
面试容易讲

```

---

# 31. 开发阶段划分

项目实现不应该一次性实现最终版。

严格按照：

```text
Baseline
↓
Correctness
↓
Redis
↓
MQ
↓
Reliability
↓
Performance

```

推进。

---

# 32. 七天开发计划

## Day 1 —— 工程骨架 + 基础业务

目标：

**让项目跑起来。**

完成：

```text
Maven Multi Module
Spring Boot
MySQL
MyBatis-Plus
Docker Compose

user
event
event_session
event_inventory
ticket_order
ticket
waiting_list

```

实现：

```text
注册
登录

创建活动
查询活动
创建Session
查询Session

```

当日验收：

```text
Spring Boot成功启动
MySQL连接成功
基础CRUD成功
JWT登录成功

```

---

# Day 2 —— NORMAL 普通抢票

重点实现：

```text
ReservationApplicationService

```

链路：

```text
校验Session
↓
MySQL条件UPDATE库存
↓
WAIT_PAY
↓
支付
↓
Ticket

```

同时完成：

```text
取消
超时
退款

```

V1 超时先：

```text
@Scheduled

```

当日必须验证：

```text
100并发
抢10张票

最终成功 <= 10
stock >= 0
没有重复Ticket

```

这一天下班时，EventHub 应该已经是一个完整可用项目。

---

# Day 3 —— Redis + Lua

完成：

```text
活动详情缓存
Session缓存

stock
occupied-users
purchased-users
reservation-map

库存预热
抢票 Lua
释放 Lua

用户级基础限流

```

FLASH：

```text
请求
↓
Redis Lua
↓
同步创建订单

```

先不接 RabbitMQ。

然后进行：

```text
MySQL版本
VS
Redis版本

```

第一次性能对比。

---

# Day 4 —— RabbitMQ

完成：

```text
RabbitMQ配置

CreateOrderMessage

OrderCreateExchange
OrderCreateQueue

Consumer

Manual ACK

Publisher Confirm

Return Callback

Retry
DLQ

```

FLASH 升级：

```text
Redis
↓
RabbitMQ
↓
MySQL

```

完成：

```text
reservationId

```

以及：

```http
GET /reservations/{id}

```

---

# Day 5 —— 最终一致性

完成：

```text
reservation_request

mq_outbox

OutboxPublisher

ReconciliationJob

Redis Compensation Lua

Consumer Idempotency

```

同时完成：

```text
ORDER_PAID
ORDER_TIMEOUT
ORDER_REFUNDED

```

相关状态同步。

这一天下班时重点测试：

```text
重复MQ消息

Consumer异常

Redis扣成功但DB失败

重复支付

重复取消

重复退款

```

---

# Day 6 —— 缓存 / 限流 / 线程池 / 监控

完成：

```text
Cache Aside

Null Cache

随机 TTL

热点重建锁

User Rate Limit

Session Rate Limit

JWT Blacklist

reconciliationExecutor

warmupExecutor

Actuator
Micrometer

```

并完善：

```text
GlobalExceptionHandler
ErrorCode
RequestId
MDC

```

---

# Day 7 —— 压测 + 优化 + 简历材料

最后一天不再疯狂加业务。

重点：

```text
JMeter
Actuator
GC Log
MySQL EXPLAIN
RabbitMQ Management
Redis Metrics

```

完成至少三轮测试：

```text
V1 MySQL

V2 Redis

V3 Redis + RabbitMQ

```

记录：

```text
QPS
P95
P99
错误率
MySQL压力
RabbitMQ积压
JVM指标

```

最后产出：

```text
README.md

architecture.md

benchmark.md

项目架构图

核心抢票时序图

数据库ER图

简历描述

面试项目介绍

```

---

# 33. 功能优先级

## P0 —— 必须完成

```text
登录
Event
Session
Inventory
NORMAL抢票
FLASH抢票
订单
模拟支付
Ticket
Redis Lua
RabbitMQ
Consumer幂等
Outbox
补偿
限流
基础压测

```

---

## P1 —— 尽量完成

```text
退款
候补
缓存击穿
JWT Blacklist
DLQ
延迟关单
线程池隔离
Actuator

```

---

## P2 —— 时间允许再做

```text
真实邮件
WebSocket
Prometheus
Grafana
二维码
复杂Admin后台
复杂RBAC
Bloom Filter
XXL-Job

```

如果七天不够：

> **砍 P2，绝对不要牺牲 P0 的完成度。**

---

# 34. 第一阶段验收标准

最终系统必须证明以下事实。

### 数据正确性

```text
库存永远不能 < 0

total =
available + locked + sold

一个用户一个Session最多一张Ticket

重复MQ不能创建重复订单

重复释放不能导致库存增加两次

```

### 高并发

例如：

```text
10000 users
100 tickets

```

最终：

```text
成功资格 <= 100
有效订单 <= 100
Ticket <= 100

```

---

# 35. 可靠性验收

至少人工制造：

### Case 1

```text
RabbitMQ重复投递

```

结果：

```text
只有一个订单

```

### Case 2

```text
支付接口连续请求10次

```

结果：

```text
只执行一次支付状态转换

```

### Case 3

```text
取消接口连续请求10次

```

结果：

```text
库存只恢复一次

```

### Case 4

```text
Consumer抛异常

```

结果：

```text
Retry
↓
DLQ / 后续补偿

```

### Case 5

```text
Reservation长时间PROCESSING

```

结果：

```text
Reconciliation最终处理

```

---

# 36. 项目核心技术难点

最终 EventHub 至少形成四个真正可讲的项目难点。

## 难点一：高并发库存

```text
MySQL条件UPDATE
↓
Redis库存前置
↓
Lua原子扣减

```

解决：

```text
超卖
热点行竞争
数据库压力

```

---

## 难点二：削峰

```text
Redis Lua
↓
RabbitMQ
↓
异步订单

```

解决：

```text
瞬时写入峰值

```

---

## 难点三：最终一致性

```text
Reservation
+
Transactional Outbox
+
RabbitMQ
+
Consumer Idempotency
+
Reconciliation
+
Compensation

```

解决：

```text
Redis
RabbitMQ
MySQL

```

跨系统数据一致性。

---

## 难点四：系统稳定性

```text
限流
幂等
缓存治理
线程池隔离
Fail Fast
监控
压测

```

避免高并发时系统整体雪崩。

---

# 37. 架构演进路线

EventHub 最终最重要的故事不是：

> “我用了很多技术。”

而是：

```text
V1
MySQL条件UPDATE
解决超卖
↓
问题：
热点库存行竞争

```

```text
V2
Redis前置库存
↓
问题：
多命令原子性

```

```text
V3
Redis Lua
↓
问题：
成功请求仍瞬时写MySQL

```

```text
V4
RabbitMQ削峰
↓
问题：
跨组件一致性

```

```text
V5
Outbox
幂等
重试
DLQ
补偿
对账
↓
最终一致性

```

然后：

```text
V6
限流
缓存治理
线程池隔离
JVM/数据库优化

```

这条演进路线是整个项目的核心。

---

# 38. 当前明确不做

V1 不引入：

```text
Spring Cloud
Nacos
Gateway
Feign
Seata
Kafka
RocketMQ
Elasticsearch
MongoDB
ShardingSphere
XXL-Job
复杂分布式锁
复杂DDD
CQRS
Event Sourcing

```

原因：

> 当前业务规模不需要，引入只会增加不必要的复杂度。

---

# 39. 项目完成定义 Definition of Done

EventHub V1 只有满足以下条件才算真正完成：

```text
项目可以通过 Docker Compose 启动依赖

Spring Boot可正常运行

数据库schema完整

NORMAL抢票可运行

FLASH抢票可运行

不存在超卖

不存在重复有效票

RabbitMQ可靠消费

补偿机制可运行

基础限流生效

核心接口有测试

完成至少一次真实JMeter压测

README解释架构演进

README明确所有性能数据来自真实测试

```

项目最终应该达到：

> **你不仅能运行它，还能解释为什么每一个关键技术存在。**

这才是 EventHub 对初级 Java 后端面试最重要的价值。
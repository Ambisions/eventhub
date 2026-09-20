# EventHub Security、Quality 与 Testing Review

仅当修改涉及：

- Authentication
- Authorization
- Validation
- Error Handling
- Logging
- Tests
- Documentation
- Maintainability

时读取。

---

# 1. Authentication / Authorization

检查：

- 需要登录的接口是否经过 Authentication
- 当前 userId 是否来自可信 Security Context
- 是否直接相信客户端传入 userId
- 用户是否可以操作其他人的 Order / Ticket

不要把：

Authentication

和：

Business Authorization

混为一谈。

---

# 2. Input Validation

HTTP 输入检查：

- Required Field
- Range
- Format
- Enum / Status
- Business ID

Validation 不能替代 Domain Rule。

Domain Rule 也不能依赖 Controller 一定验证过。

---

# 3. Sensitive Data

检查日志 / Response 是否暴露：

- Password
- JWT
- Secret
- Internal Stack Trace
- 不必要敏感 Payload

不要完整打印所有：

MQ Payload
Request Header
Security Context。

---

# 4. Exception Handling

检查：

- Business Exception 是否被正确映射
- 未预期异常是否安全输出
- 是否吞异常
- 是否 catch Exception 后假装成功

Global Handler：

不得承担库存补偿等核心业务逻辑。

---

# 5. Logging

关键业务日志应支持关联：

- requestId / traceId
- userId（必要时）
- sessionId
- frozen business key（未来 FLASH exact key 尚未冻结）
- orderNo
- messageId

但日志不能代替：

业务状态
或
持久化事实。

---

# 6. Tests

Review Test 时问：

测试是否真正能够发现当前实现的 Bug？

检查：

- Happy Path
- Boundary
- Duplicate
- Race
- Failure
- Database Constraint
- Integration

根据修改范围选择。

不要只看 Test 数量。

---

# 7. 禁止 Weak Test

警惕：

- 只 assert HTTP 200
- 只 verify 方法调用一次
- 并发功能只有单线程测试
- 幂等功能只调用一次
- MQ Reliability 全部 Mock
- Redis Lua 从未真实执行

需要时使用：

`testing-verification`

取得详细测试策略。

---

# 8. Flaky Test

Review 新增测试是否依赖：

- sleep
- Execution Order
- Shared Mutable State
- Developer Local Data
- External Unknown Environment

Flaky Test 可能隐藏真实 Race Condition。

---

# 9. Documentation

发生重要：

- Architecture
- Schema
- Redis
- MQ
- Business Flow
- Benchmark

事实变化时：

检查 Living Documentation 是否同步。

但不要要求每个小代码改动都修改架构文档。

---

# 10. Code Quality

只有真实影响维护性的代码问题才作为 Review Finding。

重点检查：

- Excessive Complexity
- Dead Code
- Duplicated Business Rule
- Ambiguous Naming
- Huge Method
- Hidden Side Effect
- Wrong Abstraction

不要把纯个人格式偏好升级成 Major Finding。

---

# 11. Temporary Artifacts

检查 Diff 是否包含：

- Debug Print
- Temporary Log
- Commented-out Code
- Test Credential
- Local Config
- Generated Garbage
- Accidental File

这些通常属于 Minor，

但 Secret / Credential 泄漏应提高严重级别。

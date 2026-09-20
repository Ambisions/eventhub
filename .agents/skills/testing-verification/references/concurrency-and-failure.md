# EventHub Concurrency、Idempotency 与 Failure Verification

仅当任务涉及：

- Concurrent Request
- Race Condition
- Idempotency
- Duplicate MQ
- Compensation
- Infrastructure Failure
- Flaky Test

时读取。

---

# 1. 真并发

并发测试必须真实产生竞争。

可使用：

- ExecutorService
- CountDownLatch
- CyclicBarrier
- CompletableFuture

例如：

所有 Worker Ready
→ Barrier Release
→ 同时执行 reserve

顺序 for 循环：

不是并发测试。

---

# 2. 不使用 sleep 猜时序

避免：

`Thread.sleep(...)`

人为猜测竞争顺序。

优先：

- Barrier
- Latch
- Future
- Explicit State

否则测试容易：

Flaky。

---

# 3. 验证最终事实

例如：

Stock = 100

Concurrent Requests = 1000

重点检查：

Successful <= 100

availableStock >= 0

Inventory Invariant 正确

Order / Reservation 数量合理。

不要只验证：

所有线程结束。

---

# 4. 幂等

如果系统声称某操作幂等：

必须主动执行多次。

例如：

- Pay x2
- Cancel x2
- Refund x2
- Timeout x2
- Same frozen business key xN（FLASH exact key 尚未冻结）
- Compensation xN

有效业务结果：

最多一次。

---

# 5. Order Race

重点验证：

Pay vs Cancel

Pay vs Timeout

Cancel vs Timeout

同一 WAIT_PAY Order：

只能有一个竞争操作获得合法状态转换资格。

---

# 6. Duplicate MQ

At-Least-Once 环境下主动制造：

Duplicate Delivery。

例如未来 FLASH 实现中，相同业务 Key 重复发送；精确 `reservation_id` 尚未冻结。

最终验证：

- One Order
- No Duplicate Side Effect
- Correct ACK / Idempotent Result

---

# 7. Compensation Duplicate

同一 Reservation 可能因为：

- Retry
- Timeout
- MQ Duplicate
- Reconciliation
- Manual Recovery

多次触发恢复逻辑。

测试必须证明：

库存不会重复恢复。

occupied 不会被错误处理。

purchased 不会被错误删除。

---

# 8. Redis Failure

FLASH：

Redis Unavailable
→ Fail Fast。

不要期待：

FLASH 自动全量降级 MySQL。

NORMAL 与 FLASH：

故障语义不同。

---

# 9. RabbitMQ Failure

根据任务验证：

- Broker Unavailable
- Publisher Retry
- Outbox Pending
- Consumer Failure
- Retry Exhausted
- DLQ
- Consumer Restart
- DB COMMIT Before ACK Crash

失败后的重点是：

最终业务事实是否仍然可以恢复。

---

# 10. Flaky Test

偶发失败必须调查：

- Race Condition
- Time Dependency
- Shared State
- Test Data Leakage
- External Service State
- Thread Cleanup
- Production Bug

不要：

Retry Test Until Green。

Flaky Test 可能正在暴露真实并发问题。

---

# 11. 失败测试修复

测试失败时先分类：

Production Bug

Test Bug

Environment Problem

Flaky Test

Obsolete Test

不要先修改 Assertion。

以确认过的：

Business Rule
+
Architecture

判断哪一方错误。

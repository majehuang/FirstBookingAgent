# P1 调度与 Worker 测试用例

## ready 消费与 lease

### SCH-001　ready 令牌唤醒对应 Worker

- 优先级：P0；层级：Redis 集成。
- 步骤：向 ready 写入一个合法 session 令牌。
- 预期：调度循环读取令牌，尝试获取该 session 的 lease，并只启动该 session 的 Worker。

### SCH-002　lease 使用 SET NX PX 且值每次唯一

- 优先级：P0；层级：Redis 集成。
- 步骤：连续触发同一 session 的两次独立抢占，记录命令与值。
- 预期：命令包含 `NX` 和正数 `PX`；两次 lease token 不同，不能只使用 pod 名或固定常量。

### SCH-003　同一 session 竞争时只有一个 Worker 执行

- 优先级：P0；层级：并发集成。
- 步骤：在单进程内用两个调度线程同时处理同一 session 的重复 ready 令牌。
- 预期：只有一个抢占成功并调用 Agent；失败方不 drain inbox、不释放胜者 lease。

### SCH-004　已有 lease 时不重复执行

- 优先级：P0；层级：Redis 集成。
- 步骤：预置一个未过期 lease，再投递该 session 的 ready 令牌。
- 预期：不创建第二个 turn，不读取/推进 inbox 游标，不覆盖已有 lease。

### SCH-005　lease 值与释放动作匹配

- 优先级：P1；层级：单元/Redis 集成。
- 步骤：Worker A 获取 lease；模拟 lease 已被替换为 B 的值，再执行 A 的释放逻辑。
- 预期：A 不得删除 B 的 lease。若 P1 暂未实现安全 compare-delete，应把该用例标记为 P3 门禁，不能伪报通过。

## Worker drain 与 Agent 接入

### WRK-001　初始化正确的 user/session 上下文

- 优先级：P0；层级：集成。
- 步骤：为两个用户的两个 session 各投递消息，用记录型 `TurnEngine` 捕获调用上下文。
- 预期：每次调用得到正确的 `(userId, sessionId)` 和消息正文；上下文不串会话。

### WRK-002　按 Redis Stream 顺序 drain inbox

- 优先级：P0；层级：Redis 集成。
- 步骤：向同一 inbox 按顺序写入三条 MESSAGE，随后只写一个 ready 令牌。
- 预期：Worker 按 Stream ID 顺序处理三条消息，不遗漏、不倒序。

### WRK-003　一次唤醒抽干当时可见的 inbox

- 优先级：P0；层级：Redis 集成。
- 步骤：预先放入多条指令，只触发一次 ready。
- 预期：Worker 循环读取直至 inbox 无未处理项，而不是每个令牌只处理一条。

### WRK-004　drain 期间到达的新消息继续被处理

- 优先级：P0；层级：并发集成。
- 步骤：Worker 处理第一条时向同一 inbox 追加第二条。
- 预期：Worker 在结束前再次检查并处理第二条；不会因一次“读空”时序把已到达消息遗留为孤儿。

### WRK-005　每条 MESSAGE 调用一次 streamEvents

- 优先级：P0；层级：集成。
- 步骤：投递三条不同 `instructionId` 的消息，使用记录型 `TurnEngine`。
- 预期：按指令顺序调用三次；输入正文完全一致；同一指令不重复调用。

### WRK-006　Agent 事件流错误被终止并产生用户可见错误

- 优先级：P1；层级：集成。
- 步骤：让 `TurnEngine` 先发一个文本事件再抛异常。
- 预期：已经成功持久化的文本保留；turn 正常结束清理；若协议要求错误消息，则 ERROR 先落库后进入 outbox；调度线程继续服务其他 session。

### WRK-007　不同 session 可并行且数据隔离

- 优先级：P1；层级：并发集成。
- 步骤：同时唤醒两个 session，让两个可控 Agent 流交错发事件。
- 预期：各自 inbox、lease、消息表、outbox 和 `msgSeq` 独立；一个 session 的慢流不阻塞另一个 session 的推进。

## P1/P3 边界检查

P1 允许非原子摘牌，因此本阶段不要求验证 pod 崩溃恢复、lease 续租、PEL 回收、`XAUTOCLAIM`、
`XCLAIM JUSTID` 心跳或 Lua 原子摘牌。这些能力若已经提前实现，可以附加测试，但不能用它们替代上述单节点基本用例。


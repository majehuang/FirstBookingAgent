# P1 Outbox 与持久化测试用例

## 合批与 delta 合并

### OUT-001　80ms 到时触发小批次写出

- 优先级：P0；层级：Reactor 单元测试。
- 步骤：使用虚拟时间发送少于 64 个事件，推进到 79ms、再推进到 80ms。
- 预期：79ms 前不写出；达到配置窗口后写出一个批次。允许调度误差时，应断言配置值而不是依赖真实墙钟。

### OUT-002　达到 64 条立即写出

- 优先级：P0；层级：单元测试。
- 步骤：在 80ms 内发送 63 条，再发送第 64 条。
- 预期：63 条时不因数量写出；第 64 条到达后立即形成一个批次；没有第 65 条混入。

### OUT-003　相邻同 blockId 的 TEXT_DELTA 被合并

- 优先级：P0；层级：参数化单元测试。
- 数据：相同 `replyId/role/blockId/type=TEXT_DELTA` 的 `A`、`B`、`C`。
- 预期：生成一条内容为 `ABC` 的 delta；最终时间/水位采用最后一段；输入对象不被修改。

### OUT-004　不同 blockId 不合并

- 优先级：P0；层级：单元测试。
- 步骤：输入相邻但 blockId 不同的两个 delta。
- 预期：输出仍为两条，顺序与输入一致。

### OUT-005　同 blockId 但不同 replyId、role 或 type 不合并

- 优先级：P0；层级：参数化单元测试。
- 数据：分别改变 `replyId`、`role`、`type`。
- 预期：每种情况均保持为两条，不能跨 turn、发言者或消息类型合并。

### OUT-006　非相邻 delta 不跨消息合并

- 优先级：P0；层级：单元测试。
- 步骤：输入 `delta(block=A)`、`TOOL_CALL`、`delta(block=A)`。
- 预期：三条顺序不变；首尾 delta 不合并。

## 写入顺序与故障

### OUT-007　消息表提交成功后才能 XADD outbox

- 优先级：P0；层级：集成/调用顺序测试；覆盖 INV-5。
- 步骤：给 Repository 与 Redis Writer 加可观测屏障，处理一个批次。
- 预期：PostgreSQL 事务成功提交事件先发生，之后才调用 outbox `XADD`；不能仅断言源码调用顺序。

### OUT-008　数据库失败时不写 outbox

- 优先级：P0；层级：故障注入。
- 步骤：令消息批次插入或事务提交失败。
- 预期：outbox 无对应消息；序号水位回滚，无永久 `msgSeq` 空洞；错误向 Worker 传播并按 turn 失败策略处理。

### OUT-009　XADD 失败时消息仍保留在数据库

- 优先级：P0；层级：故障注入；对应计划用例 `xaddFails_messageStillInDb_clientHealsGap`。
- 步骤：允许消息表提交，随后令 outbox `XADD` 失败。
- 预期：数据库记录仍存在且不回滚；该消息不可在 outbox 中伪报成功；后续消息可继续获得递增序号。超窗自愈由 P4 历史接口最终验收。

### OUT-010　批次数据库写入保持原子性

- 优先级：P0；层级：PostgreSQL 集成。
- 步骤：一个批次包含多条消息，并让中间一条因非法数据失败。
- 预期：整批均不落库，序号水位不推进，outbox 一条也不写。

## 顺序、序号与 Redis 契约

### OUT-011　大批事件在随机延迟下仍有序

- 优先级：P0；层级：并发单元/集成；覆盖 INV-8。
- 步骤：生成至少 1000 个带递增标记的事件，在持久化步骤注入不同延迟。
- 预期：最终数据库和 outbox 顺序都与源事件一致；`msgSeq` 严格递增且无洞。

### OUT-012　用 flatMap 的反证测试能够变红

- 优先级：P1；层级：架构回归测试。
- 步骤：测试夹具中使用并发 `flatMap` 处理带随机延迟的大批次，确认能观察到乱序；生产管道改为 `concatMap` 后运行同一断言。
- 预期：反序实现稳定暴露乱序，生产实现保持有序，证明测试确实能保护 INV-8，而不是永远绿色。

### OUT-013　outbox 条目可完整还原 ClientMessage

- 优先级：P0；层级：Redis 契约测试。
- 步骤：写入包含 Unicode、换行、空 payload 的 USER/TEXT 和 ASSISTANT/TEXT_DELTA，随后读取并反序列化。
- 预期：`msgSeq/replyId/blockId/role/type/fallbackText/payload/createdAt` 全部一致；同 session 使用 `KeyNamespace.outbox(sid)`。


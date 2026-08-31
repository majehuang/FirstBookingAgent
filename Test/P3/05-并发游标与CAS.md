# P3 并发背压、双游标与 AgentState 存储契约测试用例

## 在飞任务上限

### CAP-001　在飞 turn 从不超过配置上限

- 优先级：P0；层级：并发集成。
- 步骤：投递远多于上限的不同 session，用屏障阻塞已启动 turn。
- 预期：inFlight 最大值不超过 N；多余 token 留在可恢复的 ready/PEL 状态。

### CAP-002　空闲槽位为 0 时停止认领

- 优先级：P0；层级：命令观察。
- 步骤：占满全部槽位并继续写 ready。
- 预期：不继续 XREADGROUP/XAUTOCLAIM 新 token；不能先认领再放在进程内队列等待。

### CAP-003　认领数量等于空闲槽位数

- 优先级：P0；层级：参数化测试。
- 数据：空闲 0、1、N-1、N，ready 深度小于/大于空闲数。
- 预期：请求 count=`min(空闲槽位, 可配置批上限)`；不多领制造其他 pod 饥饿。

### CAP-004　turn 完成后立即释放槽位

- 优先级：P0；层级：虚拟时间。
- 预期：完成信号后计数减一并允许下一 token；不等待下一轮固定轮询才释放逻辑容量。

### CAP-005　异常、取消、lease lost 都释放槽位

- 优先级：P0；层级：生命周期参数化。
- 预期：所有终止路径只释放一次，不负数、不泄漏；大量失败后仍能继续认领新工作。

### CAP-006　多个 pod 各守本地上限且公平取任务

- 优先级：P1；层级：多 pod。
- 步骤：3 个 pod 设置相同 N，投递足够多 session。
- 预期：每 pod 不超 N，总并发不超 3N；无单 pod 因预取占住远超 N 的 PEL。

### CAP-007　慢 session 不阻塞其他空闲槽位

- 优先级：P1；层级：并发集成。
- 步骤：一个 turn 长时间阻塞，其余 turn 快速完成。
- 预期：只占一个槽位；其他槽位持续流转，不能因单 session drain 串行化整个 dispatcher。

## 双游标与 instructionId 幂等

### CUR-001　msg 与 ctrl 使用独立字段

- 优先级：P0；层级：Redis 集成。
- 预期：同一 cursor hash 中 `msg`、`ctrl` 独立推进；更新一个不覆盖另一个，不把 ctrl 指令当普通消息跳过。

### CUR-002　cursor key 不设置 TTL

- 优先级：P0；层级：Redis 命令/TTL 检查。
- 步骤：创建和推进双游标，查询 PTTL。
- 预期：返回无过期语义；pod 重启和长时间无活动后游标仍存在。

### CUR-003　session 显式删除时清理 cursor

- 优先级：P1；层级：生命周期集成。
- 步骤：执行正式 session 删除流程。
- 预期：只删除目标 session cursor，并与会话其他资源按冻结顺序清理；普通退出或 pod 重启不删除。

### CUR-004　处理成功后才推进 msg cursor

- 优先级：P0；层级：故障注入。
- 步骤：分别在解析、Agent、消息落库、outbox 阶段失败。
- 预期：按冻结的可重试边界推进；未完成指令不能被 cursor 永久越过，解析坏消息的跳过策略有明确记录。

### CUR-005　重复 instructionId 只认领一次 turn

- 优先级：P0；层级：PostgreSQL + Redis。
- 步骤：inbox 中放入同 instructionId 的多条记录。
- 预期：USER 消息唯一，`claimTurn` 只成功一次，其他记录安全推进 cursor 且不调用 Agent。

### CUR-006　多 pod 并发幂等不依赖先查后写

- 优先级：P0；层级：并发真库。
- 步骤：绕过正常 lease 保护制造两个 pod 同时处理同 instructionId。
- 预期：数据库唯一索引仍阻止重复 USER；turn claim 阻止第二轮；msgSeq 不因失败竞争留下洞。

### CUR-007　pod 重启从持久游标继续

- 优先级：P0；层级：重启集成。
- 步骤：处理部分 inbox 后硬杀并重启/接管。
- 预期：已完成指令不重复回复；未完成指令可恢复；msg 与 ctrl 各从自己的水位继续。

## AgentState 存储契约（不要求 CAS）

> 2026-08-28 决议：防双跑只由 lease（INV-3）保证，AgentStateStore 仅负责共享持久化并允许 LWW。
> `CAS-*` 编号为兼容既有追踪矩阵而保留，不代表产品要求实现 CAS。

### CAS-001　生产装配使用 PostgreSQL AgentStateStore

- 优先级：P0；层级：装配测试。
- 预期：多 pod 不使用 InMemory/JsonFile store；相同 user/session 的状态由共享 PostgreSQL 可见。

### CAS-002　普通状态写入按 LWW 生效

- 优先级：P0；层级：PostgreSQL 集成。
- 步骤：同一 user/session/key 连续写入两个状态，不携带 expectedVersion。
- 预期：后写状态可读；不要求版本匹配、冲突异常或重试流程。

### CAS-003　AgentStateStore 契约不要求版本参数

- 优先级：P0；层级：架构契约。
- 步骤：检查 AgentScope 实际使用的 `AgentStateStore.get/save` 方法签名和生产装配路径。
- 预期：状态读写无需暴露 expectedVersion；缺少版本化 API 不构成失败或阻断。

### CAS-004　并发写者仲裁只依赖 lease

- 优先级：P0；层级：多 Worker 集成。
- 步骤：两个 Worker 竞争同一 session，让一个获得 lease，另一个获取失败；再模拟持有者续租失败。
- 预期：任一时刻只有持有 lease 的 Worker 能写；失败者不启动 turn，丢牌者由 LeaseFence 停止后续写入；断言不依赖 AgentState CAS。

### CAS-005　P3 验收不得依赖 CAS

- 优先级：P0；层级：架构门禁。
- 步骤：检查生产调用链、P3 任务卡与验收报告；确认没有把 `saveIfVersion()`、版本冲突或 CAS 重试列为防双跑依赖。
- 预期：共享 PostgreSQL + LWW 即满足 AgentStateStore 要求；P3-13 标记完成，lease（INV-3）是唯一防双跑机制。

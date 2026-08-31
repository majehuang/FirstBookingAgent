# P3 摘牌 Lua、Lease 与 Worker 收尾测试用例

## 摘牌 Lua

### LUA-001　摘牌脚本独立成文件且说明原子性

- 优先级：P0；层级：架构检查。
- 预期：存在独立 `unlease.lua` 资源；注释说明“校验持有、确认 inbox 已空、删除 lease”必须在同一脚本完成，不能拆成客户端命令。

### LUA-002　启动时 SCRIPT LOAD 并缓存 SHA

- 优先级：P0；层级：Redis 集成。
- 步骤：启动两个 pod，记录 Redis 命令。
- 预期：启动阶段加载脚本并缓存返回 SHA；业务路径不每次发送完整脚本文本。

### LUA-003　运行期使用 EVALSHA

- 优先级：P0；层级：命令契约。
- 步骤：完成一次正常 Worker 收尾。
- 预期：摘牌调用使用已加载 SHA；key 和 argv 数量、顺序与冻结契约一致。

### LUA-004　NOSCRIPT 后安全 reload

- 优先级：P1；层级：故障注入。
- 步骤：`SCRIPT FLUSH` 后触发摘牌。
- 预期：识别 NOSCRIPT，按冻结次数重新 SCRIPT LOAD/EVALSHA；不能跳过所有权和 inbox 检查直接 DEL。

### LUA-005　持有者匹配且 inbox 已空时删除 lease

- 优先级：P0；层级：真 Redis Lua。
- 步骤：预置当前 token 的 lease，保证 msg 游标已到 inbox 尾部，执行脚本。
- 预期：脚本返回“已摘牌”，lease 不存在；其他 session key 不受影响。

### LUA-006　lease 值不匹配时拒绝删除

- 优先级：P0；层级：真 Redis Lua；覆盖 INV-3。
- 步骤：A 持旧 token，Redis 中 lease 已是 B 的 token，A 执行摘牌。
- 预期：脚本返回“非持有者”；B 的 lease 原值与 TTL 保持有效。

### LUA-007　inbox 未抽干时拒绝摘牌

- 优先级：P0；层级：真 Redis Lua；覆盖 INV-2。
- 步骤：当前 token 匹配，但在 msg 游标之后仍有 inbox 条目。
- 预期：lease 保留，脚本返回“仍有工作”；Worker 继续 drain，不能 ACK ready。

### LUA-008　释放瞬间到达消息不会成为孤儿

- 优先级：P0；层级：并发真 Redis；INV-2 专属用例。
- 步骤：用屏障让 `XADD inbox` 与摘牌脚本竞争，重复至少 1000 次。
- 预期：若消息先进入，脚本看到非空并保留 lease；若脚本先完成，投递方随后写 ready。所有消息最终均被处理。

### LUA-009　Lua 使用的 session key 位于同一 hash slot

- 优先级：P0；层级：键契约/Redis Cluster。
- 步骤：计算 lease、inbox、cursor 等脚本 key 的 slot，并在 Cluster 测试实例执行。
- 预期：同一 session 的脚本 key hash tag 相同，不产生 CROSSSLOT；全局 ready 不传入该脚本。

### LUA-010　Lua 原子性测试禁止 mock

- 优先级：P0；层级：构建守卫。
- 预期：测试连接 Redis 7.0 Testcontainer 并执行真实脚本；容器不可用时测试失败或明确标为环境阻断，不能以 mock 绿灯替代。

## Lease、收尾顺序与续租失败

### LSE-001　同 pod 两次抢占使用不同 lease token

- 优先级：P0；层级：Redis 集成；覆盖 INV-3。
- 步骤：同一 pod 上两个 Worker 先后抢占同一 session。
- 预期：token 每次随机唯一，不能只用 podName；旧 Worker 不能释放新 Worker 的 lease。

### LSE-002　获取 lease 使用 SET NX PX

- 优先级：P0；层级：命令契约。
- 预期：命令同时包含 NX 与正 TTL；已有 lease 时不覆盖 token、不重置 TTL、不启动第二个 turn。

### LSE-003　续租只允许当前 token

- 优先级：P0；层级：真 Redis Lua/原语。
- 步骤：分别使用正确 token、过期 token、其他 session token 续租。
- 预期：仅当前 token 延长 TTL；失败返回 false，不可用先 GET 后 PEXPIRE 的非原子实现。

### LSE-004　释放只删除匹配 token

- 优先级：P0；层级：真 Redis。
- 预期：匹配 token 可删除；不匹配 token 不删除；释放操作不得影响新接管者。

### LSE-005　收尾顺序固定为抽干→摘牌→XACK

- 优先级：P0；层级：调用顺序；覆盖 INV-4。
- 步骤：记录 drain 完成、unlease 脚本成功、ready XACK 三个时刻。
- 预期：严格按该顺序发生；摘牌返回“仍有工作/非持有者”时不得 XACK。

### LSE-006　lease 每 10 秒续租且任务在 doFinally 取消

- 优先级：P0；层级：Reactor 虚拟时间。
- 步骤：推进多个续租周期，再让 turn 正常完成、失败、取消。
- 预期：持牌期间按周期续租；所有终止信号后不再发续租命令，没有后台永久续租。

### LSE-007　续租失败立即中止 turn

- 优先级：P0；层级：故障注入；覆盖 INV-3/INV-4。
- 步骤：让一次续租返回 false，TurnEngine 正在持续生成事件。
- 预期：立即取消/interrupt 当前 turn，不等模型自然结束；状态记录 lease lost，旧 Worker 不再执行业务写入。

### LSE-008　续租失败后停止写消息表与 outbox

- 优先级：P0；层级：屏障集成。
- 步骤：在 lease lost 前后各准备一条事件，用写入屏障精确控制。
- 预期：失败前已提交消息可保留；失败后事件不进入消息表和 outbox；旧持有者不能污染新持有者输出。

### LSE-009　续租失败不错误推进游标或 ACK

- 优先级：P0；层级：恢复集成。
- 步骤：续租失败后检查 msg cursor、ready PEL 和 ACK 状态，再启动接管 pod。
- 预期：未完成工作仍可被接管；旧 Worker 不 ACK 令牌、不把游标推进到未完成指令之后。


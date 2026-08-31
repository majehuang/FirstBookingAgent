# P3 多节点正确性测试用例

> 状态：测试设计稿，不代表当前实现已经通过。
> 依据：[开发规划.md](../../开发规划.md) P3、[开发计划.md](../../开发计划.md) P3-1～P3-15。

## 1. 测试目标

验证任意 pod 可以处理任意 session，执行节点退出后能够自动接管，并在故障和竞争下保持：

- 无消息丢失。
- 无重复回复。
- 无 session 永久卡死。
- 同一 session 同时只有一个合法写入者。

P3 分成两个子里程碑：

```text
P3a 机制建成
  → 投递终态
  → 原子摘牌 Lua
  → 消费组与 consumer 生命周期
  → PEL 心跳、XAUTOCLAIM、死 consumer 清理
  → lease 续租失败中止
  → 背压、双游标、AgentState 共享持久化（LWW）

P3b 正确性验证
  → 四条不变量反证
  → kill pod / Redis 抖动 / 长 GC / 网络分区
  → G3 混沌验收
```

## 2. 文件索引

| 文件 | 覆盖范围 |
|---|---|
| [01-投递与幂等.md](01-投递与幂等.md) | inbox→ready、故障终态、同 instructionId 重试 |
| [02-摘牌Lua与Lease.md](02-摘牌Lua与Lease.md) | 原子摘牌、SCRIPT LOAD/EVALSHA、lease 唯一值、收尾顺序、续租失败 |
| [03-消费组与心跳.md](03-消费组与心跳.md) | XGROUP 初始化、consumer 命名、XCLAIM JUSTID 心跳 |
| [04-回收清理与裁剪.md](04-回收清理与裁剪.md) | XAUTOCLAIM、DELCONSUMER、ready MAXLEN 安全 |
| [05-并发游标与CAS.md](05-并发游标与CAS.md) | inFlight 背压、msg/ctrl 双游标、instructionId 幂等、AgentState lease-only 存储契约 |
| [06-混沌与G3验收.md](06-混沌与G3验收.md) | 四条不变量反证、故障注入、恢复 SLA、G3 记录 |

## 3. 测试环境

| 层级 | 工具 | 外部依赖 |
|---|---|---|
| 纯逻辑/生命周期 | JUnit 5、AssertJ、Reactor Test、虚拟时间 | 无 |
| Lua 与 Redis 原语 | Testcontainers + Redis **7.0** + Lettuce reactive | Docker |
| 状态与消息持久化 | PostgreSQL Testcontainers | Docker |
| 网络故障 | Toxiproxy/Testcontainers 网络 | Docker |
| 多 pod E2E | 至少 3 个 worker 进程或容器 | Redis + PostgreSQL |
| 混沌长跑 | 可控 Agent、进程 kill、延迟/断网/长暂停注入器 | 隔离环境 |

统一约定：

- Java 21。
- Lua 原子性用例必须连接真实 Redis，不接受 mock。
- Redis 版本固定为生产购买档 Redis 7.0；另设 Redis 6.2 最低兼容测试。
- 每条用例使用唯一 runId、podName、consumerName、sessionId 和 instructionId。
- 时间逻辑优先使用可注入时钟或 Reactor 虚拟时间；真实 Redis idle/TTL 用例允许短测试配置，不能等待生产级 60 秒或 1 小时。
- 只清理本次 runId 命名空间，不执行 `FLUSHALL`、`FLUSHDB` 或删除共享消费组。
- 所有顺序断言基于 Redis Stream ID、命令日志、数据库记录和故障屏障，不能依赖线程调度碰巧正确。
- 混沌用例必须固定随机种子并保存失败种子，确保可复现。

## 4. 需求追踪矩阵

| 计划项 | 测试用例 |
|---|---|
| P3-1 投递终态 | `DLV-001`～`DLV-008` |
| P3-2 摘牌 Lua | `LUA-001`～`LUA-010` |
| P3-3 消费组初始化 | `GRP-001`～`GRP-003` |
| P3-4 consumer 生命周期 | `GRP-004`～`GRP-007` |
| P3-5 持牌期 PEL 心跳 | `HBT-001`～`HBT-006` |
| P3-6 XAUTOCLAIM 回收 | `RCV-001`～`RCV-010` |
| P3-7 死 consumer 清理 | `CLN-001`～`CLN-007` |
| P3-8 ready 裁剪 | `TRM-001`～`TRM-005` |
| P3-9 收尾顺序 | `LSE-001`～`LSE-005` |
| P3-10 lease 续租失败中止 | `LSE-006`～`LSE-009` |
| P3-11 在飞任务上限 | `CAP-001`～`CAP-007` |
| P3-12 双游标与幂等 | `CUR-001`～`CUR-007`、`DLV-004`～`DLV-007` |
| P3-13 AgentState 存储契约（共享 PG + LWW，不要求 CAS） | `CAS-001`～`CAS-005` |
| P3-14 混沌测试台 | `CHA-001`～`CHA-008` |
| P3-15 混沌验收 | `CHA-009`～`CHA-012` |
| INV-1 | `DLV-001`～`DLV-007`、`CHA-001` |
| INV-2 | `LUA-005`～`LUA-008`、`CHA-002` |
| INV-2b | `RCV-001`～`RCV-010`、`TRM-004`、`CHA-005` |
| INV-2c | `HBT-001`～`HBT-006`、`CHA-006` |
| INV-2d | `CLN-001`～`CLN-007` |
| INV-3 | `LSE-001`～`LSE-004`、`CHA-003` |
| INV-4 | `LSE-005`～`LSE-009`、`CHA-004` |

## 5. 自动化前必须冻结的契约

- `unlease.lua` 的参数、返回码和“inbox 非空”判定口径。
- `NOSCRIPT` 时由哪个组件 reload，最多重试几次，重试是否允许跨 Redis 节点。
- ready Stream key、group 名 `workers`、consumerName 的 pod 名来源和非法名称处理。
- 生产时间参数与测试时间参数的注入接口：lease TTL、续租周期、PEL 心跳、MIN-IDLE、回收周期、consumer 清理阈值。
- 续租失败后的精确语义：何时调用 `interrupt()`，已落库消息是否保留，哪些游标不得推进，ready 令牌由谁接管。
- 优雅停机的超时与到期策略：停止认领、等待在飞任务、主动交接或让 TTL 接管。
- P3-13 已冻结为 lease-only：AgentStateStore 仅负责共享 PostgreSQL 持久化并允许 LWW，
  不要求版本/CAS 或冲突重试；同一 session 防双跑只由 lease（INV-3）保证。
- G3 恢复 SLA 已冻结：优雅停机主动交接 ≈ 0；硬杀恢复上界 90 秒
  （`XAUTOCLAIM MIN-IDLE=60s` + 最长一个 30 秒回收周期）。

## 6. P3 明确不测的能力

- outbox 超窗历史恢复与 `turnStartId` 裁剪保护：P4。
- control SSE、跨节点 cancel、停止和重跑：P5。
- 大规模 SSE、配额、429 自适应和生产容量上限：P6。
- P3 只验证 ready 的安全裁剪配置，不替代 P4 的 outbox 裁剪测试。

## 7. G3 放行标准

- P3a 所有 P0 用例通过，Lua/回收/清理不能用 mock 或静态检查代替真 Redis。
- INV-1、INV-2、INV-3、INV-4 各有一条先红后绿的专属故障注入用例。
- 至少 3 个 worker 持续运行混沌套件，无消息丢失、无重复回复、无永久卡死。
- 优雅停机用户无感知；硬杀恢复满足冻结后的 SLA。
- PEL 最终回到基线，死 consumer 元数据被安全清理，所有 lease 最终释放或过期。
- `mvn clean verify` 通过，无未解释跳过，新增模块行覆盖率不低于 80%。
- 验收报告保存随机种子、时间线、Redis/数据库快照、进程退出码和恢复延迟分布。

# P1 单节点闭环测试用例

> 状态：测试设计稿，不代表当前实现已经通过。
> **实现进度在 GitHub Issues 上跟踪**（里程碑 `G1 放行`，issue #9–#14），本目录只是规格。
> 依据：`开发规划.md` P1、`开发计划.md` P1-1～P1-8、`docs/客户端协议.md` v1.1。

## 1. 测试目标

验证单节点下以下主链路完整可用：

```text
POST /sessions/{sid}/messages
  → inbox
  → ready
  → lease + drain
  → HarnessAgent.streamEvents
  → 消息表
  → outbox
  → GET /sessions/{sid}/events（SSE）
```

同时验证 P1 的三个关键约束：

- `INV-1`：先写 inbox，后写 ready，两步都成功才返回 `202`。
- `INV-5`：用户可见消息先落消息表，再写 outbox；冷存储失败不影响 turn。
- `INV-8`：事件管道使用 `concatMap`，并发批次下仍保持顺序。

## 2. 文件索引

| 文件 | 覆盖范围 |
|---|---|
| [01-接口与投递.md](01-接口与投递.md) | HTTP、鉴权、参数校验、幂等、inbox/ready |
| [02-调度与Worker.md](02-调度与Worker.md) | ready 消费、lease、drain、Agent 调用 |
| [03-Outbox与持久化.md](03-Outbox与持久化.md) | 合批、delta 合并、消息表、outbox、故障顺序 |
| [04-SSE.md](04-SSE.md) | SSE 线格式、全量窗口重放、实时扇出、资源释放 |
| [05-事件冷存储.md](05-事件冷存储.md) | AgentEvent 异步旁路与故障隔离 |
| [06-端到端与G1验收.md](06-端到端与G1验收.md) | 全链路、多轮、刷新续接、流畅度、门禁 |

## 3. 分层与运行环境

| 层级 | 推荐工具 | 外部依赖 |
|---|---|---|
| 单元测试 | JUnit 5、AssertJ、Reactor Test、Mockito/手写 Fake | 无 |
| HTTP/SSE 契约测试 | Spring/Servlet 对应测试工具、WebTestClient 或真实 HTTP Client | 启动应用 |
| Redis 集成测试 | Testcontainers Redis **7.0**、Lettuce reactive | Docker |
| PostgreSQL 集成测试 | Testcontainers PostgreSQL、真实迁移脚本 | Docker |
| 端到端测试 | Redis + PostgreSQL + 可控 `TurnEngine` | Docker |
| 人工验收 | Redis + PostgreSQL + 真实模型 | 模型凭据 |

统一约定：

- 编译和运行使用 Java 21。
- 每个自动化用例生成唯一 `userId`、`sessionId`、`instructionId`，避免并行污染。
- 时间相关测试使用可注入时钟或 Reactor 虚拟时间，禁止用长时间 `sleep`。
- Redis 集成测试结束后按本用例的 key 精确清理，不执行全库 `FLUSHALL`。
- 所有顺序断言必须基于可观测调用日志、Redis Stream ID 或数据库记录，不能依赖线程调度“看起来正确”。

## 4. 需求追踪矩阵

| 计划项 | 测试用例 |
|---|---|
| P1-1 HTTP 接口、鉴权、参数校验、异步 `202` | `API-001`～`API-009` |
| P1-2 inbox → ready | `DLV-001`～`DLV-007` |
| P1-3 ready 消费 + lease | `SCH-001`～`SCH-005` |
| P1-4 初始化、drain、调用 Agent | `WRK-001`～`WRK-007` |
| P1-5 OutboxWriter | `OUT-001`～`OUT-013` |
| P1-6 消息 SSE | `SSE-001`～`SSE-011` |
| P1-7 冷存储旁路 | `COLD-001`～`COLD-006` |
| P1-8 联调和流畅度 | `E2E-001`～`E2E-007` |
| INV-1 | `DLV-001`～`DLV-004`、`E2E-005` |
| INV-5 | `OUT-007`～`OUT-010`、`COLD-002`～`COLD-004` |
| INV-8 | `OUT-011`、`OUT-012`、`E2E-006` |

## 5. P1 明确不测的能力

以下能力属于后续阶段，不作为 G1 阻塞项：

- 多节点竞争、pod 崩溃接管、`XAUTOCLAIM`、lease 心跳和原子摘牌：P3。
- outbox 超窗历史恢复、`turnStartId` 裁剪保护、历史 HTTP 接口完整验收：P4。
- 控制 SSE、停止/重跑和 `ctrlId` 水位：P5。
- 卡片、图片、语音等富消息端到端渲染：P2。
- 大规模 SSE 连接、配额和限流压测：P6。

## 6. 自动化前必须冻结的接口细节

规划尚未规定以下线格式。实现前应补入正式接口文档，测试中不得自行创造永久契约：

- HTTP 鉴权方式及 `401`/`403` 的错误体。
- 参数错误、会话不存在、内部失败的统一错误 JSON。
- inbox 和 ready Stream 的字段名及序列化格式。
- SSE 的 `event` 名称、心跳格式以及能力协商的承载位置。
- 冷存储 Writer 接口、重试策略和允许的最终一致时间。

在这些细节冻结前，对应用例可以验证语义，不应建立字段快照。

## 7. G1 放行规则

- P0/G0 中除明确批准的云探针阻塞外，无影响 P1 的开放项。
- 本目录中 P0/P1 优先级的自动化用例全部通过，无不说明原因的跳过。
- `mvn clean verify` 通过，模块行覆盖率不低于项目门禁。
- `E2E-001`、`E2E-002`、`E2E-003`、`E2E-006` 使用真实 Redis/PostgreSQL 通过。
- `E2E-007` 使用真实模型人工通过，并归档时间、配置、日志摘要和结果。


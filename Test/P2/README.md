# P2 消息渲染测试用例

> 状态：测试设计稿，不代表当前实现已经通过。
> 依据：[开发规划.md](../../开发规划.md) P2、[开发计划.md](../../开发计划.md) P2-1～P2-7、
> [客户端协议.md](../../docs/客户端协议.md) v1.1。

## 1. 测试目标

验证富消息从模型表达型工具到客户端显示的完整链路：

```text
模型调用 send_hotel_cards
  → onActing 从 toolCalls() 取入参
  → 服务端补全酒店数据
  → withMetadataEntry 写入完整卡片
  → render(toolName, payload)
  → MessageAdapter 保持文本/卡片顺序
  → 消息表冻结 CARD
  → outbox/SSE
  → 客户端展示“数据截至 …”
```

P2 重点保护三条不变量：

- `INV-5`：补全后的完整卡片先落消息表，再进入 outbox。
- `INV-7`：middleware/adapter 中的阻塞补全必须 offload，BlockHound 零告警。
- `INV-8`：文本、工具、卡片、追问严格按事件顺序处理。

## 2. 文件索引

| 文件 | 覆盖范围 |
|---|---|
| [01-render与协议.md](01-render与协议.md) | `render()` 纯函数、CARD 协议、能力降级、异常载荷 |
| [02-MessageAdapter.md](02-MessageAdapter.md) | per-reply reducer、文本累积、边界 flush、销毁 |
| [03-工具与Middleware.md](03-工具与Middleware.md) | `send_hotel_cards`、`onActing`、服务端补全、BlockHound |
| [04-冻结持久化.md](04-冻结持久化.md) | 完整卡片落库、outbox 顺序、重开不重查、逐字节一致 |
| [05-接入模板与前端.md](05-接入模板与前端.md) | 一种富消息一组 tool+middleware、文档、数据时间 UI |
| [06-端到端与G2验收.md](06-端到端与G2验收.md) | 酒店推荐全链路、顺序、刷新、G2 验收记录 |

## 3. 推荐测试环境

| 层级 | 推荐工具 | 依赖 |
|---|---|---|
| 纯函数单元测试 | JUnit 5、AssertJ、参数化测试 | 无 |
| Reactor 生命周期测试 | Reactor Test、虚拟时间 | 无 |
| Middleware 测试 | AgentScope 测试替身、BlockHound | 无/服务 Fake |
| 持久化集成测试 | PostgreSQL Testcontainers | Docker |
| 富消息 E2E | Redis 7.0 + PostgreSQL + 脚本化 Agent | Docker |
| 前端契约测试 | JSON 快照、组件测试、视觉快照 | 前端工程 |

统一约定：

- Java 21。
- 固定测试时间为 `2026-08-24T10:00:00Z`，避免快照随墙钟变化。
- 服务端补全使用可计数 Fake，必须能断言调用次数和入参。
- 数据库与 Redis 用例使用唯一 session，不能依赖测试执行顺序。
- JSON 一致性比较同时保留两种口径：语义 JSON 相等和线格式逐字节相等；G2 使用后者。
- 所有顺序用 `msgSeq`、事件序号或调用日志断言，不能只看最终 UI “大致正确”。

## 4. 需求追踪矩阵

| 计划项 | 测试用例 |
|---|---|
| P2-1 `ClientMessage` + 纯函数 `render()` | `REN-001`～`REN-012` |
| P2-2 `MessageAdapter` 生命周期 | `ADP-001`～`ADP-011` |
| P2-3 `send_hotel_cards` | `TOOL-001`～`TOOL-008` |
| P2-4 `onActing` + BlockHound | `MID-001`～`MID-011`、`BH-001`～`BH-004` |
| P2-5 完整卡片冻结落库 | `FRZ-001`～`FRZ-011` |
| P2-6 富消息接入模板 | `TPL-001`～`TPL-006` |
| P2-7 数据时间 UI | `UI-001`～`UI-007` |
| G2 酒店推荐验收 | `E2E-001`～`E2E-008` |
| INV-5 | `FRZ-001`、`FRZ-006`～`FRZ-009` |
| INV-7 | `MID-008`～`MID-011`、`BH-001`～`BH-004` |
| INV-8 | `ADP-005`～`ADP-009`、`E2E-002`、`E2E-006` |

## 5. 自动化前必须冻结的契约

规划只规定语义，尚未规定以下线格式：

- `send_hotel_cards` 的输入 JSON Schema 和工具返回值中 `shown` 的确切类型。
- middleware 使用的 metadata key、版本号及载荷结构。
- `render(toolName, payload)` 对未知工具、缺字段和补全失败的返回类型。
- 酒店卡片 item 的必填字段、价格币种格式、时间格式和时区。
- 客户端能力信息通过哪个 Header、查询参数或建连消息上报。

上述内容冻结后，应加入协议/Schema 快照测试。冻结前可以验证语义和不变量，但不能把测试夹具中的临时字段变成事实标准。

## 6. P2 明确不测的能力

- pod 崩溃接管、消费组回收、lease 正确性：P3。
- outbox 超窗恢复和历史接口：P4。
- 控制 SSE、停止与重跑：P5。
- 大规模连接数、准入和配额压测：P6。
- 图片、音频的真实编解码和对象存储：若 P2 只交付接入模板，则仅做模板契约测试。

## 7. G2 放行标准

- `mvn clean verify` 通过，P2 自动化用例无未解释跳过。
- BlockHound 在 P2 middleware/adapter 集成测试中零告警。
- 酒店推荐输出严格为：推荐理由 → 一个包含三张酒店卡片的组件 → 一句追问。
- CARD 消息数据库记录包含服务端补全后的完整载荷和数据时间。
- 关闭页面重开后，不调用酒店查询服务，卡片线格式与首次生成逐字节一致。
- 不支持 CARD 的客户端获得 TEXT 降级，`msgSeq/replyId/role` 不变。


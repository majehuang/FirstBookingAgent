# P0 地基阶段 TODO

> 依据《开发规划.md》P0 交付与 2026-08-24 代码审查结果整理。
> **当前结论：六项完成，一项阻塞（阿里云实例探针，需访问权限）。**
> 验收证据见 [`docs/G0-验收记录.md`](../../docs/G0-验收记录.md)。

## 1. 恢复构建与测试基线　✅

- [x] 修复 `ClientMessage` 增加 `role` 字段后，测试仍调用旧构造器的问题。
- [x] 更新协议 JSON 兼容性测试，为 v1.1 消息补齐 `role`。
- [x] 检查 `agent-protocol`、`agent-store`、`agent-engine`、`agent-tui` 中所有旧构造器调用。
- [x] 执行 `mvn clean verify`，确保编译、单元测试和覆盖率门禁全部通过。

**结果**：285 项测试通过，五个模块覆盖率门禁全绿。不设 `AGENT_IT_*` 时集成测试整体跳过，构建仍通过。

## 2. 固化 Redis 键命名规范　✅

- [x] 新增统一的 `KeyNamespace` 实现（`agent-keys` 模块），禁止业务模块自行拼接 Redis key。
- [x] 将分片数 `256` 固化为常量，并注明修改分片数等同于数据迁移。
- [x] 定义稳定的 session 分片算法。
- [x] 七类 key 的构造方法（inbox / outbox / cursor / lease / state / ctrl-stream / ready）。
- [x] 添加键命名快照测试，覆盖固定 sessionId、特殊字符和分片边界。
- [x] 添加架构守护测试（`KeyLiteralGuardTest`），扫描全部生产源码阻止硬编码键名。

**两个需要留意的实现决定**：

- **分片算法用 FNV-1a 而非 `String.hashCode()`。** 后者虽有 Java 规范保证，但低位对 `% 256`
  分布不均，且换语言实现时只能靠"照抄 JDK"。FNV-1a 是完全写在代码里的十几行运算。
  实测 10 万个 id 的桶大小落在 332–459（理想 390）。
- **hash tag 用三位零填充 `{s000}`–`{s255}`。** 规划 C 节的 `{s07}` 是示意写法；
  取值 0–255 时两位会溢出成不定长，而不定长前缀会让运维按前缀扫描漏掉一部分 key。
  **这是需要签字冻结的格式决定。**

守护测试第一次运行就抓到四个占位命令把键名当文档字面量打印。已改为从 `KeyNamespace` 现算 ——
打印出来的契约不会再和实现漂移。

## 3. 补全客户端消息协议　✅

- [x] 定义协议版本（`Protocol.VERSION = "1.1"`）与能力协商模型（`ClientCapabilities`）。
- [x] 明确默认能力（**只保证文本**）与不支持富消息时的服务端降级行为。
- [x] 处理未知 `MessageType` / `MessageRole`：新增 `UNKNOWN` + `@JsonEnumDefaultValue`。
- [x] 增加未知类型、未知字段、空 `fallbackText` 与能力协商测试。
- [x] 为协议消息与投递回执增加 JSON 快照测试（`WireFormatSnapshotTest`）。
- [x] 重试约定写入正式协议文档 [`docs/客户端协议.md`](../../docs/客户端协议.md) §6。
- [x] 统一 v1/v1.1 版本描述（代码、README、协议文档）。

**修掉的一个真 bug**：Jackson 遇到未知枚举值会直接反序列化失败 ——
等于服务端一上线新消息类型，所有老客户端在解析那一刻整条流断掉，
`fallbackText` 的降级设计根本走不到。

**新增的一条校验**：富消息（CARD / IMAGE / AUDIO）的 `fallbackText` 不可为空。
降级路径上它是唯一剩下的东西，空的话客户端看到一行空白，比看到粗糙的文本更糟。

## 4. 保证 msgSeq 每 session 单调无洞　✅

- [x] 重新设计序号分配与消息插入边界：`append` 在**单事务**内分配并写入。
- [x] 幂等判断、序号分配、用户消息插入纳入同一临界区（分配器行 `FOR UPDATE`）。
- [x] 批量写入的事务行为：整批成功，或序号水位一步不推进。
- [x] 相同 `instructionId` 并发重试测试（8 线程）。
- [x] 插入失败故障测试（注入不可序列化载荷）。
- [x] 多 session 交错写入测试。
- [x] 真实 PostgreSQL 集成测试验证事务、唯一索引与并发行为。

**修掉的两个真 bug**：

1. **`allocate` 与 `append` 分属两个事务**，写入失败会烧掉序号并留下永久空洞。
   现在序号由消息表在事务内分配，引擎侧完全不碰它 —— API 上就无法把两者拆开。
2. **8 线程并发重试同一 `instructionId` 烧掉 7 个序号**（下一条拿到 9 而不是 2）。
   根因是"查幂等键"与"分配序号"之间是一段无保护的 check-then-act。
   我原本判断"lease 会挡住这个竞态"，但 lease 是 P3 才有的东西 ——
   现用分配器行锁把这段临界区串起来，不再依赖尚未存在的机制。

**端到端证据**：真实模型、跨两个进程、三轮对话含一次工具调用，msgSeq 严格 1..59 无洞。

## 5. 收敛 HarnessAgent 与分布式存储方案　✅

- [x] 对 `RedisDistributedStore` 与 PostgreSQL `DistributedStore` 的偏差作正式决策：**保留 PostgreSQL**。
- [x] 决策与理由写回《开发规划.md》B 节修订 + G 节决策表。
- [x] 明确单例生命周期：抽出 `TurnEngine` 接口，一个无状态实例服务所有 session。
- [x] 装配测试（`AgentScopeBackendTest`）验证多 session 共用同一引擎、出站流互不串扰。
- [x] 验证 `RemoteFilesystemSpec + IsolationScope.USER` 跨进程共享长期记忆。
- [x] 记录 RemoteFilesystem 只代理特定路径前缀的边界；shell 与 filesystem 工具默认关闭。

**保留 PostgreSQL 的理由**：上游 `AgentStateStore` 是阻塞接口，天然贴合 JDBC；
上游只有 InMemory / JsonFile 两个实现，
无论选哪个后端都得自研 —— 那就选更合适的那个。
**2026-08-28 已确认不要求版本/CAS**，状态写入允许 LWW，防双跑只由 lease（INV-3）保证；
`version` 列若保留，仅用于计数、迁移或排障。
代价是每轮推理有阻塞 JDBC 落在响应式链路上，整条 agent 流必须 `subscribeOn(boundedElastic)`（INV-7）。

**修掉的一个真 bug**：「上一轮未结束」的检查排在幂等检查之前。
而客户端超时重试最可能发生的时刻恰恰是上一轮还在跑的时候 ——
那时重试会收到异常而不是原来的回执，客户端要么继续重试要么放弃，两条路都不对。

## 6. 完成云环境与 G0 验收　🚧 一项阻塞

- [ ] **在阿里云 Redis 7.0 目标规格实例执行探针** —— 阻塞，需要实例访问凭据。
- [ ] 确认实例架构为标准版主从，并记录连接模式（直连/代理）。
- [ ] 实测并记录云上的 `EVAL`、`SCRIPT LOAD`、`EVALSHA` 及脚本内 `XADD ... *`。
- [ ] 保存云环境实测报告。
- [x] 配置真实 PostgreSQL 并运行所有存储集成测试。
- [x] 使用真实模型完成单进程多轮对话。
- [x] 重启进程后，使用相同 `(userId, sessionId)` 继续对话并验证上下文恢复。
- [x] G0 验收命令、环境前置条件与结果写入 [`docs/G0-验收记录.md`](../../docs/G0-验收记录.md)。

**为什么本地通过不算数**：阿里云 Redis 是厂商自己的构建，可能对 Lua 有额外限制
（尤其代理模式下的语法检查）。拿到实例后跑：

```bash
./bin/agent doctor --redis redis://<云实例地址>:6379 --skip-db
```

**若"脚本内 `XADD ... *`"在云上失败**：INV-11 不成立，P5 控制通道设计需重做。
不影响 P1 开工（单节点闭环不依赖 Lua），但**必须在 P3 开工前拿到结论**。

## G0 阶段门禁

- [x] `mvn clean verify` 通过，覆盖率不低于 80%。
- [x] Redis 键名、256 分片、`msgSeq` 和客户端协议已经冻结并受测试保护。
- [x] HarnessAgent 与分布式存储的实现和规划一致（偏差已正式写回规划）。
- [ ] 阿里云 Redis 实测报告已经归档 —— 🚧 待实例访问权限。
- [x] 单进程多轮对话通过。
- [x] 进程重启后，相同 `(userId, sessionId)` 可以继续对话。
- [x] P0 文档与当前代码状态一致。

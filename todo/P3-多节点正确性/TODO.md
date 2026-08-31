# P3 多节点正确性 —— 待办

> 依据：[开发规划.md](../../开发规划.md) E 节 P3、[开发计划.md](../../开发计划.md) P3-1～P3-15、
> 测试设计稿 [Test/P3](../../Test/P3/README.md)。
>
> 本轮交付的是 **P3a（机制建成）**。P3b（混沌验证）尚未开始，G3 **未放行**。

---

## 一、两个待决问题　✅ 均已定案

- [x] **恢复 SLA 与回收参数的矛盾**（CHA-011）—— **2026-08-26 决议：方案 A**

  规划原文"硬杀恢复 ≈ lease TTL（30s）"作废。**对外承诺改为：硬杀接管上界 90 秒**
  （MIN-IDLE 60s + 回收周期 30s），参数保持不动。

  推翻原文的理由：lease 过期只让新持有者*能*抢牌，**没有唤醒令牌就没有人会去抢**。
  而且 50–90 秒是每次硬杀的常态，不是尾部情况。

  敢放宽的前提是**同时补了优雅停机**（见第二节）：生产里的"pod 死亡"绝大多数是发布，
  交接把这部分降到 ≈ 0，90 秒只覆盖真正的非预期死亡。
  **⚠️ 若哪天去掉优雅停机，这个 SLA 必须重议** —— 那会让每次发布 × 每个在飞 session 吃满 90 秒。

  否决方案 B（参数调紧到 20s 级）的理由：它同时把 lease TTL 压到 10 秒，
  对 GC 停顿的容忍度下降三倍。恢复时延与停顿容忍度是同一个旋钮的两端。

  已落地：`开发规划.md` E 节 P3 修订、`开发计划.md` G3 门禁、`docs/模块契约.md`、
  `docs/客户端协议.md` §6（对外 SLA 与"不要重试"的客户端义务）、
  `TaskTimings` 类注释与 `TaskTimingsTest.恢复时延上界不等于租约时长`。

- [x] **P3-13 AgentState 并发语义正式化**（CAS-001～CAS-005）—— **2026-08-28 已定案：不要求 CAS**

  `AgentStateStore` 只承担多 pod 共享持久化，生产使用 PostgreSQL，普通 LWW 写入即可。
  不要求 `expectedVersion`、版本化读取、CAS 或冲突重试。

  **结论：防双跑的唯一机制是 lease（INV-3）。存储层 CAS 不是第二道防线，也不是 P3 验收条件。**
  数据库即使保留 `version` 列，也只用于计数、迁移或排障。

  已落地：`开发规划.md`、`开发计划.md`、`docs/模块契约.md` 和 `Test/P3` 均统一为 lease-only。
  现有 `AgentStateCasContractTest` 名称为历史遗留，测试意图是钉住“不要求 CAS”的契约。
  **P3-13 已完成，不再作为阻断项。**

---

## 二、优雅停机　✅ 已完成

- [x] **停机三段式**：停止认领 → 等在飞跑完（宽限期 20s）→ 剩下的主动交接

  `ReadyDispatcher.shutdown()` + `TurnHandoff` + `ActiveTurns`。
  交接四步：落闸 → 释放 lease → 重投唤醒令牌 → 交差旧令牌，
  **②③ 顺序不可颠倒**（先重投会让接管方在牌子还没释放时扑空，把唤醒白白交差掉）。

  测试：`GracefulShutdownIntegrationTest`（4 条）、`TurnHandoffIntegrationTest`（6 条）、
  `ActiveTurnsTest`（4 条）。

  顺带修掉一个 JVM 层面的坑：shutdown hook 原先只 `countDown` 就返回，
  而清理跑在主线程上 —— JVM 在"所有 hook 结束"时就退出，会把交接砍在半路，
  且进程是**正常退出**、日志里毫无异常。现在 hook 放行主线程后反过来等回执（40s 上限）。

- [ ] **仍未覆盖**：SIGTERM 下持续投递时"无 5xx 风暴"、多 pod 滚动更新的用户无感知。
  这两条要真起进程，留在 P3b 混沌套件（CHA-009 完整版）。

---

## 三、兜底：持牌上限　✅ 已完成

> 方针：单条消息滞留可以接受（用户重发即可）；**要避免的是用户无论怎么发都没人理**。

- [x] **持牌上限**（`TaskTimings.maxLeaseHold` = 15 分钟）

  重发之后仍然没人理，只有一个成因：**牌子一直不放**。而牌子占着不放只有两种可能 ——
  持有者死了（TTL 30s 自动过期，已有），或者**持有者活着但这一轮卡死**：
  续租一拍一拍续下去，牌子永不过期。第二种是 TTL 唯一救不了的，也是唯一真缺口。

  只有持牌方自己知道"我拿着这个牌子多久了"，所以判定放在续租循环里：
  超过上限就停止续租并落闸，牌子在一个 TTL 内消失，用户下一条消息即可被正常处理。

  卡住那一轮不写收尾消息（不知道它卡在哪儿）——
  **目标是让会话恢复可用，不是让这一轮起死回生。**

  测试：`WedgedTurnRecoveryIntegrationTest`（3 条）。

- [x] ~~滞留 session 清扫器（SCAN 全部 inbox）~~ —— **已撤回**

  它解决的是"这一条消息滞留了"，恰好是可以让用户重发的那类；
  代价是扫全键空间 + 给 inbox 加属主字段 + 过载时会全量重投把负载翻倍。
  **投在了错的那一半问题上。** 连同属主字段与键名反解一并回退。

- [x] **可观测指标** —— `HealthProbe` + `QueueHealth` + `HealthLog`，
  周期与维护循环同频（30s），健康时不出声。

  七个数分全局/本机两组，三条告警规则（最老 pending idle > 5min、
  最长持牌 > 上限 80%、槽位满载）。已在真 worker 进程上验证告警确实出现在 stderr。

  **过程中发现一个会让整套观测归零的问题**：`agent-cli` 绑的是 `slf4j-nop`，
  `log.warn` 在发布出去的二进制里什么都不输出。改为走 `HealthLog` 自己写 stream
  （与 `TurnLog` 同一个理由）。

- [ ] **续租失败次数** 与 **lease 持有时长 P99** 等累计型指标：
  当前只有瞬时快照，没有计数器与分位数。要做分位数得先有一个指标注册表（P6-8 的 Micrometer），
  这一轮没引入。

---

## 四、P3b 混沌验证　⏸ 已移出自动化范围（2026-08-28：改人工执行）

> **2026-08-28 决定：混沌测试改由人工执行，不做自动化。**
> `agent-it` 模块不再建；**P3-14（混沌测试台）与 P3-15（混沌验收执行）移出自动化交付范围**。
> 下面 CHA-005～012 保留在此，仅作**人工执行清单**，不计入 `mvn verify`，不阻塞交付。

**四条不变量反证已全部完成**，它们不需要多进程，已在自动化里。

> **G3 的"四条不变量各有一条先红后绿的专属用例"这一项已满足**：
> CHA-001 / CHA-003 / CHA-004 在 `InvariantCounterProofIntegrationTest`，
> CHA-002 在 `UnleaseScriptIntegrationTest`（必须紧挨 `unlease.lua`）。
> 每条都成对：先用错误实现把故障稳定造出来，再用正确实现证明它消失。

- [x] **CHA-001** INV-1 反证 —— 反序投递丢唤醒 + 正序转绿 + 重试自愈
- [x] **CHA-002** INV-2 反证 —— 非原子摘牌造孤儿（在 `UnleaseScriptIntegrationTest`）
- [x] **CHA-003** INV-3 反证 —— podName 当 lease 值的反例可稳定复现，唯一值下转绿
- [x] **CHA-004** INV-4 反证 —— 先 ACK 后摘牌的缝隙确实不留线索，正确顺序下留下可回收令牌
- ⏸ ~~**P3-14 混沌测试台**~~ —— 移出自动化范围
- 👤 **CHA-005** 持续对话中随机 SIGKILL 执行 pod，固定种子
- 👤 **CHA-006** 对 XREADGROUP / 续租 / 心跳 / EVALSHA / XAUTOCLAIM 分别注入延迟与 reset
- 👤 **CHA-007** 暂停持牌 pod 超过 TTL 与回收阈值，验证旧 pod 恢复后立即自停
- 👤 **CHA-008** 网络分区隔离一个持牌 pod
- 👤 **CHA-009** 优雅停机对用户无感知 —— 实现已完成（第二节），缺多进程 E2E 验证
- [x] **多 worker 基本验证** —— `MultiWorkerIntegrationTest`（3 worker / 12 session / 36 轮，
  真 PG）。**抓到一个真 bug**：`Flux.interval + concatMap` 被慢工作永久打死，
  导致回收/健康/续租三条循环静默停跑。已修（`Periodic`）并有红绿反证。
  修复前 5 次红 1 次，修复后连跑 12 次全绿
- 👤 **CHA-010** ≥3 pod、100 session 长跑（进程级、带故障注入）
- 👤 **CHA-011** 硬杀恢复延迟采样 ≥100 次，报告 P50/P95/P99/max
- 👤 **CHA-012** 混沌结束后资源回到基线

**完成标准**：人工执行后把结果填进 [`docs/G3-验收记录.md`](../../docs/G3-验收记录.md) 第二节。
**本节不阻塞自动化交付。**

> 人工里最该优先跑的是 **CHA-011 硬杀恢复延迟采样** ——
> 90 秒上界是算出来的、一次没实测过，而它已经写进协议文档给客户端看了。

---

## 五、实现上仍缺的部分

- [ ] **CUR-003** session 显式删除时清理 cursor

  游标刻意不设 TTL（否则空闲久的 session 被唤醒会重放全部历史指令），
  代价是必须有一条显式清理路径。目前**没有** session 删除流程，游标会永久留存。

- [ ] **`claimTurn` 是一次性的，夭折的轮次不会自动重跑**（多 worker 测试中观察到）

  一轮被 `claimTurn` 认领之后如果中途夭折（持牌方丢牌 → 落闸中止），
  那条指令<b>永远不会再被处理</b>：USER 消息在、`turn_claimed_at` 已置位、但没有回复。
  按既定方针这属于"交给用户重发"的范畴，测试也已改为断言真正的承诺
  （`assertResendAlwaysWorks`）。**但这是个尚未坐实的推断** ——
  我只观察到"回复数少于指令数"，没有直接验证成因就是 claimTurn。
  要坐实需要查 `turn_claimed_at` 与 reply 的对账。

  若将来要修：夭折时回滚 claim（`turn_claimed_at = NULL`）比重发更彻底，
  但要小心它与"避免重复回复"的关系。

- [ ] **`MaintenanceCycle.dispatchClaimed` 的订阅没有被跟踪**

  它用 `Flux…subscribe(…)` 把回收到的令牌交出去，这个订阅<b>不在 `running` 里</b>，
  因此 `close()` 不会取消它 —— 优雅停机之后它仍可能在写 cursor/outbox。
  多 worker 测试跑完后测试库里残留 12 组 key，就是这么来的。
  影响有限（写的都是合法内容），但它绕过了停机的"停止认领"语义。

- [ ] **RCV-007** 多页回收的压力验证（≥1000 pending，混合未过期/已过期/已删除条目）

  当前多页回收只验到 40 条（`PelMaintenanceIntegrationTest.多页回收不漏`）。
  已删除 entry 的 deleted-ID 语义还没被真正压过 —— `PendingReclaimer` 的
  `MAX_PAGES` 保险丝就是为这个不确定性留的。

---

## 六、本轮已完成（供交叉核对，不再重做）

| 计划项 | 落地 | 测试 |
|---|---|---|
| P3-1 投递终态 | `RedisInstructionPublisher`（P1 即按最终顺序） | **DLV-002/003/004**（真 WRONGTYPE 注入）+ 协议文档 §6（DLV-008） |
| P3-2 摘牌 Lua | `redis/unlease.lua` + `ScriptRegistry` + `LeaseGuard.unlease` | LUA-002～009、CHA-002 反证 |
| P3-3 消费组初始化 | `ReadyDispatcher.ensureGroup`，起点 `0`，只吞 BUSYGROUP | GRP-001/002 |
| P3-4 consumer 名 | `ConsumerName`，校验 + 不加后缀 | GRP-004/005 |
| P3-5 PEL 心跳 | `PelHeartbeat`，与续租同频 | HBT-002/003/004/006 |
| P3-6 XAUTOCLAIM 回收 | `PendingReclaimer`，启动一次 + 每 30s，游标循环 | RCV-004～006/008/010 |
| P3-7 死 consumer 清理 | `ConsumerJanitor` + `MaintenanceCycle` 保证顺序 | CLN-001/003/004/005/007 |
| P3-8 ready 裁剪 | `StreamLimits` 集中定义 | TRM-001/004 |
| P3-9 收尾顺序 | `WorkOutcome` 决定 ACK 资格 | LSE-005（单测层） |
| P3-10 续租失败中止 | `LeaseFence` 双防线 + `WriteGate` | LSE-007/008 |
| P3-11 在飞上限 | `InFlightSlots`，认领数 = 空闲槽位 | CAP-001～005 |
| P3-12 双游标与幂等 | `Cursors` + `claimTurn`（P1 已有），游标推进受闸门保护 | CUR-001/002/005 |
| P3-13 AgentState 存储契约 | **已完成**：共享 PG + LWW，不要求 CAS，防双跑只靠 lease | CAS-001～005 |
| CHA-009 优雅停机 | `shutdown()` + `TurnHandoff` + `ActiveTurns` | 14 条，见第二节 |
| INV 反证 | 四条不变量各一对红/绿用例 | CHA-001/002/003/004 |
| 兜底：持牌上限 | 卡死的 turn 主动放弃执行权，防会话永久失聪 | 3 条，见第三节 |

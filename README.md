# java-agent-sdk

分布式 Agent 服务。**模块之间没有 RPC、没有服务发现、没有共享内存 —— 接口就是那张 Redis key 表。**
这是通信层与任务层可以独立部署、独立扩容的前提，也是整套设计最主要的收益。

当前形态是命令行，**运行形态只有两种**：`chat`（默认）与 `worker`。
默认那种把两者放进同一个进程，两半之间照样只经由 Redis 交换数据 ——
省的是第二个终端，不是那条边界。后续接 servlet 时只换门面，模块间的契约一行不动。

| 文档 | 内容 |
|---|---|
| [`开发规划.md`](./开发规划.md) | 设计：存储分层、键命名、模块划分、12 条不变量 |
| [`开发计划.md`](./开发计划.md) | 排期、任务卡、阶段门禁、执行记录 |
| [`docs/客户端协议.md`](./docs/客户端协议.md) | 协议 v1.1，**冻结文档** |
| [`docs/G0-验收记录.md`](./docs/G0-验收记录.md) | 可复现的验收命令与结果 |
| [`docs/模块契约.md`](./docs/模块契约.md) | 收消息／发消息／任务控制三个模块读写哪些 key、守哪些不变量 |
| [`docs/富消息接入.md`](./docs/富消息接入.md) | 接入一种新富消息的四步与两个坑 |
| [`Test/P1/`](./Test/P1/) | P1 测试用例设计稿（60 个用例） |

**进度在 [Issues](https://github.com/majehuang/FirstBookingAgent/issues) 上跟踪。**
`Test/P1/` 与 `todo/` 是设计稿，issue 才是待办的真相源 —— 两处不一致时以 issue 为准。

---

## 进度

| 阶段 | 状态 |
|---|---|
| **P0 地基** | ✅ 六项完成，一项阻塞（阿里云实例探针，需访问权限） |
| **P1 单节点闭环** | ✅ 主链路跑通，自动化用例待补（见 `Test/P1/`） |
| **P2 消息渲染** | ✅ G2 通过：卡片端到端、冻结一致、BlockHound 零告警、状态条控制状态、链路追踪 |
| P3 多节点 / P4 断线重连 / P5 控制通道 / P6 容量降级 | 未开始 |

**P1 已具备**：投递、唤醒、执行权、抽干、合批落库、outbox 扇出、全量重放、冷存储旁路。
**P1 不具备**：崩溃接管（没有 `XAUTOCLAIM`／心跳／原子摘牌，那是 P3）、
跨节点打断（P5）、超窗历史补齐（P4）、HTTP 门面与鉴权。

---

## 快速开始

```bash
mvn -q -DskipTests package        # 产出 agent-cli/target/agent.jar
./bin/agent --help
```

### 一、备好基础设施

```bash
docker compose -f deploy/redis/docker-compose.yml up -d

# 连接参数一次 export，两个进程共用（完整清单见「环境变量」一节）
export AGENT_JDBC_URL=jdbc:postgresql://localhost:5432/agent
export AGENT_DB_USER=admin
export AGENT_DB_PASSWORD=...
export AGENT_API_KEY=sk-...

# 已有 PG 实例时直接指过去；没有的话用 deploy/postgres/ 起一个
./bin/agent migrate --create-database
./bin/agent doctor
```

`doctor` 一次跑完 Redis 与 PG 两侧，退出码 0 表示全部通过、2 表示存在阻塞项。
它是开发计划 P0-1 的自动化版本：引擎版本、架构、`EVAL`/`EVALSHA`、
**脚本内 `XADD ... *`**、`XAUTOCLAIM`、`XTRIM MINID`，以及 PG 的表结构与序号分配原子性。

### 二、两种运行形态

```bash
./bin/agent                 # 默认：TUI + 内嵌 worker，一个进程就能跑通
./bin/agent --sole          # 只起客户端，推理交给外部 worker
./bin/agent worker          # 只起 worker，为了看日志
```

**默认那种不是捷径。** TUI 与内嵌 worker 之间照样走 Redis：
投递写 inbox+ready，输出读 outbox，状态条读 ctrl-stream。
如果内嵌形态走的是进程内直连，那"单进程能跑通"就完全不能说明分布式形态能跑通 ——
而分布式才是要交付的形态。省掉的只是第二个终端。

多节点或者想单独看 worker 日志时拆成两个进程：

```bash
# 终端 A —— 任务层（可多开，水平扩展）。引擎参数配在这一侧
./bin/agent worker --engine scripted                        # 可控引擎，端到端测试用
./bin/agent worker --tools hotel \
    --provider openai --model kimi-for-coding --base-url https://api.kimi.com/coding

# 终端 B —— 客户端
./bin/agent --sole --session s-1
```

两个进程之间**没有任何直接调用**。数据流见下方[「Redis 数据流」](#redis-数据流)。

> ⚠️ **内嵌 worker 消费的是全局唤醒队列，不只是你这个会话。**
> 同一个 Redis 上有别人时，你的进程可能用你的模型参数去跑别人的会话。
> 单机开发无所谓，共用 Redis 时请用 `--sole` 加独立 worker。
> consumer 名固定为 `主机名-pid` —— 消费组按 consumer 名分配令牌，
> 本机开两个会话时重名会让消息"有时没人处理、有时处理两次"。

### 三、真引擎的凭据

```bash
export AGENT_API_KEY=...
./bin/agent --provider openai --model kimi-for-coding \
    --base-url https://api.kimi.com/coding
```

`--provider openai` 覆盖所有 OpenAI 兼容端点（Kimi / DeepSeek / GLM / MiniMax），
DashScope 用 `--provider dashscope`，`auto` 交给上游 SPI 按模型名解析。
三条路径都会把已解析的 Key 与 `--base-url` 交给 provider ——
`auto` 曾经不会（走的是 `resolve(modelName)` 单参重载，内部是空 context），
症状是"守卫说 Key 有，上游说 Key 没有"。

凭据只从环境变量读（`AGENT_API_KEY`，或 `DASHSCOPE_API_KEY` / `OPENAI_API_KEY`）——
命令行参数会进 shell 历史，也会出现在 `ps` 输出里。
**每个参数都有对应的环境变量**，顺序是 命令行 > 环境变量 > 默认值 ——
完整清单见[「环境变量」](#环境变量)。默认形态下同一套引擎参数要喂给两个地方
（内嵌 worker，以及可能另起的 `agent worker`），一条 `export` 比两条命令上各写一遍可靠。

**两个工具开关默认关闭，要开必须显式开：**

| 开关 | 关闭的理由 |
|---|---|
| `--enable-shell` | 本期不用沙箱（规划 D4），命令直接在 pod 上执行 |
| `--enable-filesystem` | 同上。而且实测下来模型可以经 `../` 一路列到宿主机根目录 —— 模型输出由用户输入驱动，等于把一个信息泄露面交给了不可控的一方 |

workspace 默认在 `~/.agent-cli/workspace`（框架默认是 `./.agentscope/workspace`，
会在当前目录里长出一棵目录树，在项目根下跑一次就污染仓库）。

---

## 状态条

```
✎ 生成中 1.4s  ·  ⌁ r-2cad459a-441  ·  ctrl …140230-0  ·  s-local  ·  seq 2  ·  redis    ^C 停止  ^D 退出  /help
```

除了阶段与耗时，还摆着**控制通道的状态**：

| 段 | 含义 |
|---|---|
| `⌁ r-…` | 当前活跃的 replyId，用来和 worker 的追踪对上 |
| `ctrl …140230-0` | ctrlId 水位。重连重放的起点，控制通道出错时先看它 |
| `⇱ /stop 已发出` | 控制指令已投递、服务端还没认下 |
| `⌾ 输入锁定` | 没有 turn 在跑却不让输入 —— 这是卡住了，不是正常状态 |

控制通道（快照 + 水位 + 重放）是本项目最难自证正确的一条链路，
它出错的典型表现是重连后 `turnActive` 翻转，而那在滚动区里**看不见**。
摆在状态条上，这类问题才有可能被当场发现。

宽度不够时**按优先级逐段丢弃**，而不是从右边一刀截断 ——
截断先砍掉的是书写顺序排在最后的段，与它值不值得看无关。
宽度一律按**终端列数**算：中文一个字占两列，按字符数排版会撑破终端而折行。

## 会话内命令

会话模式是默认用途，`agent` 与 `agent chat` 是同一件事；会话内的操作走斜杠命令：

| 命令 | 作用 |
|---|---|
| `/new [sessionId]` | 开新会话，不带参数则生成 id。**回复进行中会被拒绝** —— 切走之后那一轮的输出再也看不到 |
| `/session <sessionId>` | 切到指定会话 |
| `/stop` | 停止当前回复。空闲时只提示，不发无用指令 |
| `/status` | 会话、连接、水位、空窗、在途输入 |
| `/trace [on\|off]` | 运行时开关链路追踪。**不带参数报状态而不是切换** —— 敲两次不该把它悄悄关掉 |
| `/doctor` | Redis 与 PG 自检，等同 `agent doctor` |
| `/keys` | 打印**当前会话真实用到的**键，可以直接贴进 `redis-cli` |
| `/clear` | 清屏，不影响服务端历史 |
| `/help` | 命令列表 |
| `/quit` | 退出（别名 `/exit` `/q`） |

键位：`^C` 停止当前回复（不退出），`^D` 退出。

`/keys` 打出来是这样，末尾那两行是排查时最常用的判断：

```
会话 dev/s-1 用到的键
  inbox      {s169}:sess:s-1:inbox       本进程写，worker 抽干
  ready 队列 ready                        唤醒队列，全局共用一条
  outbox     {s169}:sess:s-1:outbox      worker 写，本进程订阅
  ...
  没有回复时的看法：inbox 有条目而 outbox 没有 = worker 没接上；
  两边都空 = 投递就没成功；lease 挂着不动 = 上一轮没收尾。
```

**一级命令只有四条：**

| 命令 | 是什么 |
|---|---|
| `chat` | 会话模式（默认，不带子命令即是）。TUI + 内嵌 worker，`--sole` 只起客户端 |
| `worker` | 任务层进程。不接受输入，每完成一轮打一行日志 |
| `migrate` | 建库建表，幂等，跑完就退 |
| `doctor` | Redis + PG 自检，退出码 0/2，可当 CI 门禁 |

原先的 `ingress` / `egress` / `dispatcher` 三条已删除 —— 它们跑起来只打印一段契约然后退出，
是文档伪装成命令。契约进了 [`docs/模块契约.md`](./docs/模块契约.md)，
键的真实形态用 `/keys` 看。

### 富消息

`--tools hotel` 装上酒店场景的表达型工具。问「北京有哪些酒店？展示给我看」会得到：

```
⚒ search_hotels()
✓ search_hotels 返回 4 条
┌ 北京 4 家可选酒店
│ 1. 北京国贸大酒店  ¥1,280  4.8★  含双早
└ 数据截至 2026-08-24 10:00
已为你展示…结合你的偏好，我最推荐…
```

卡片内容在生成时**冻结落库**，重开会话逐字节一致且不重查业务源 ——
所以 UI 必须标注数据时间，否则用户会把三天前的房价当成今天的。
接入新的富消息见 [`docs/富消息接入.md`](./docs/富消息接入.md)。

### 可控引擎

`--engine scripted` 取代模型，走**完整链路**（Redis + 落库 + 渲染），给定输入必产出同一串事件：

| 输入前缀 | 行为 |
|---|---|
| `!burst:N` | 产出 N 个带序号片段，验证顺序与合批 |
| `!error` | 先产出内容再失败，验证已落库内容被保留 |
| `!empty` | 不产出任何事件 |
| 其它 | 回显 `收到：<原文>`，按 2 字一段切开 |

端到端断言用它而不是真模型：真实模型既不确定又要花钱，
拿它验证"1000 个片段是否保持顺序"会又慢又偶发失败。它也不需要任何 API Key。

原先还有一个 `loopback` 后端（进程内假后端，零依赖，只验 TUI）。
它随 `--backend` 一起删了 —— 留着它就得同时留着两套 turn 生命周期实现，
而"单进程能跑通"本来就不该由一条绕开 Redis 的路径来证明。

### 脚本化验证

非 tty 环境自动降级为逐行模式，输出是可 diff 的纯文本：

```bash
printf '你好\n/status\n/quit\n' | ./bin/agent --engine scripted --session s-1
```

逐行模式下会等本轮结束再读下一行，并在退出前**从消息表对账**补齐还没轮询到的消息 ——
管道不会像人一样等着看结果。

### 链路追踪

一条消息从投出去到用户看见，中间跨两个进程、七个 key、四层存储。任何一环静默失败，
现场都只有"机器人不理我"。六个环节可以各自落痕：

| 环节 | 打在哪个进程 |
|---|---|
| 指令写入 inbox | 会话进程 |
| ready 令牌被抢到（含执行权归属） | worker |
| turn 启动 | worker |
| turn 内每个 step 事件 | worker |
| 控制帧进入 ctrl-stream（带 ctrlId 水位） | worker |
| 消息进入 outbox（原始载荷） | worker |

两个进程**同一个开关**：`--trace`。会话侧显式传 `--plain` 时会隐含打开它。
进了会话之后用 `/trace on|off` 随时开关 —— 没有它的话，为了看一眼链路就得重启，
而重启就丢会话上下文，"重现一次"往往才是排查里最难的那步。

默认形态下追踪的两个落点（客户端那一环、内嵌 worker 那五环）由 `/trace` **一起**开关：
只开一个的话链路是断的，而"看到前半段、后半段没有"最容易被误判成后半段挂了。

> 注意：管道或 IDE 控制台下终端会**自动降级**成逐行模式，但那不会开启追踪 ——
> 隐含开启只看 `--plain` 这个标志本身。在 IDEA 里要看追踪，显式加 `--trace`。

```bash
export AGENT_DB_USER=admin AGENT_DB_PASSWORD=...   # 一次，两个进程共用

./bin/agent worker --engine scripted --trace       # 终端 A
./bin/agent --sole --trace                         # 终端 B
```

一个进程也能看全六环 —— 默认形态下内嵌 worker 的五环就在同一个进程里：

```bash
./bin/agent --engine scripted --trace
```

两侧日志都带毫秒时间戳前缀，合起来 `sort` 就是一条跨进程的完整链路：

```bash
cat tui.trace worker.trace | grep -v SLF4J | sort
```

> ⚠️ 同一毫秒内的多行，`sort` 按字符串比而不是按发生顺序 —— 跨进程的先后没问题
> （网络跳一次远超 1ms），但要看**一轮之内**的准确顺序，单看 worker 那个文件，
> 它本身就是发出顺序。
>
> ⚠️ 交互式 TUI 下把追踪重定向到文件再 `tail -f`。JLine 独占终端写入，
> 追踪从 Reactor 线程写 stderr，两个生产者抢同一块屏幕会让光标乱跳。

会话进程只经手「写 inbox」这一环，另外五环在 worker 侧 ——
默认形态下两者同进程，`--trace` 与 `/trace` 会把两个落点一起开关。

两个终端并排，按 sessionId 对齐成一条完整链路：

```
[11:05:39.969] tui     → inbox  s-1  1787627139966-0  {"instructionId":"i-c59b…","kind":"MESSAGE",…}
[11:05:40.108] worker  ✦ ready  s-1  执行权已抢到，租约 30s
[11:05:40.215] worker  ▶ turn   s-1  replyId=r-2cad459a-441 instructionId=i-c59b… seq=1
[11:05:40.231] worker  ← outbox s-1  1787627140229-0  {"msgSeq":1,"replyId":"r-2cad…","role":"USER",…}
[11:05:40.232] worker  ⇄ ctrl   s-1  {"activeReplyId":"r-2cad…","ctrlId":"1787627140230-0","phase":"THINKING",…}
[11:05:40.239] worker  · step   s-1  r-2cad459a-441  REASONING text="收到"
[11:05:40.263] worker  ← outbox s-1  1787627140261-0  {"msgSeq":2,…,"type":"TEXT_DELTA",…}
[11:05:40.264] worker  ⇄ ctrl   s-1  {"ctrlId":"1787627140262-0","phase":"DONE",…,"turnActive":false}
```

`▶ turn` 那行带着 **engine=**，一眼能看出接没接上模型：

```
▶ turn  s-local  replyId=r-c973ceb6-a91 … engine=kimi-for-coding          ← 真模型
▶ turn  s-local  replyId=r-21843ead-64f … engine=scripted（不调模型，只回显输入）
```

三点值得留意：

- **追踪走 stderr，不走 stdout。** 逐行模式的 stdout 是可 diff 的验收产物，
  掺进追踪就没法比对了；交互式终端上两者都可见，不影响观察
- **打的是原始载荷，不是摘要。** 客户端渲染出问题时，第一件要确认的就是服务端到底发了什么。
  超过 512 字符会截断并**标注截掉多少** —— 静默截断在这里比不打还糟
- **默认关闭。** 热路径上每条消息都会走过埋点，关闭时连载荷都不会构造

---

## 模块

```
agent-protocol   协议 v1.1：ClientMessage / UserInstruction / ControlFrame / 能力协商
agent-keys       Redis 键命名的唯一实现：KeyNamespace + 256 分片
agent-trace      链路追踪埋点。零依赖 —— 六个环节散在四个模块里，它必须谁都能引用
agent-redis      Redis 原语：连接、流载荷编解码、lease、双游标
agent-store      PostgreSQL：消息表（真相源）、序号分配、事件冷存储、自定义 DataSource
agent-engine     AgentScope 适配：HarnessAgent 装配、事件映射、PG 版两个 Store、可控引擎
agent-tools      业务工具与富消息：表达型工具 + 补全 middleware + 渲染器
agent-comm       收消息模块 + 发消息模块
agent-task       任务控制模块 + Worker + OutboxWriter + 冷存储旁路
agent-tui        终端会话界面
agent-cli        命令行入口与组装根
```

依赖方向（每行列出该模块直接依赖的内部模块）：

| 模块 | 依赖 |
|---|---|
| `agent-cli` | comm · task · engine · tui · redis · store · keys —— 组装根，认识所有人 |
| `agent-task` | engine · redis · store · keys · protocol · trace |
| `agent-comm` | redis · store · keys · protocol · trace |
| `agent-tools` | engine · protocol |
| `agent-engine` | store · protocol |
| `agent-redis` | keys · protocol |
| `agent-store` | protocol |
| `agent-tui` | protocol · trace |
| `agent-keys` | 无 —— 它是契约本身，不能依赖任何东西 |
| `agent-trace` | 无 —— 同上，它要能被任何一层引用 |

**`agent-comm` 与 `agent-task` 互相看不见** —— 两者都没声明对方，
想直接调用会**编译失败**。这比写一条测试来守要硬：规划 D 节要求它们只经由 Redis 交换数据，
Maven 的依赖图就是这条约束的执行者。

其余边界也都是有意留的：`agent-store` 不认识 AgentScope，`agent-keys` 不认识任何业务，
`agent-tui` 不认识数据库 —— TUI 要能在没有任何后端时独立跑起来。

过去这里有一处层次倒置（`agent-engine → agent-tui`）：进程内后端 `AgentScopeBackend`
实现的端口住在 `agent-tui` 里，于是引擎依赖了终端界面。它随那个后端一起删掉了 ——
内嵌 worker 也走 Redis 之后，进程内直连这条路径不再存在，
`AgentScopeBackend` 与它的 `SessionChannel` 都成了死代码。
**turn 生命周期从两份实现变成一份**（只剩 `SessionWorker`），是这轮清理最大的一笔。

---

## 关键设计

### Redis 数据流

```
客户端                      Redis                        worker
  │                                                        │
  ├─ XADD inbox ──────────→ {sNNN}:sess:<sid>:inbox         │
  ├─ XADD ready ──────────→ ready（全局，不分片）───────────→│ XREADGROUP
  ├─ 落 USER 消息 ────────→ PostgreSQL                      │
  │  ← Ack(replyId,msgSeq)                                  ├─ SET NX PX lease
  │                                                         ├─ 抽干 inbox（循环到读空）
  │                                                         ├─ 原子认领 turn
  │                                                         ├─ 推理 → concatMap → 合批 80ms
  │                                                         ├─ 合并相邻 delta
  │                        PostgreSQL ←──────────────────── ├─ ① 先落库
  │  ← XRANGE 全量重放 ─── {sNNN}:sess:<sid>:outbox ←─────── ├─ ② 后 XADD
  │  ← 非阻塞轮询跟随                                        ├─ 摘牌（比值删除）
  │  ← state + ctrl-stream ←──────────────────────────────  └─ XACK
```

四条不变量在这条链路上：

| 不变量 | 落地点 | 违反后果 |
|---|---|---|
| **INV-1** inbox 先、ready 后 | `RedisInstructionPublisher` | 顺序颠倒 → 摘牌时检查不到新消息 → 丢唤醒 |
| **INV-5** 先落库、后 XADD | `OutboxWriter.persistThenPublish` | 反序 → 用户看到了、刷新后消失 |
| **INV-8** 事件管道 `concatMap` | 全项目唯一的 `flatMap` 在 `ReadyDispatcher` | 批次乱序，小批量看不出、批稍大就错位 |
| **INV-3** lease 值每次唯一 | `LeaseGuard`，释放与续租都用 Lua 比值 | 同 pod 的另一个 worker 误摘他人的牌子 → 双跑 |

投递是**两条命令而非原子操作**：单个全局 `ready` 与分片 `inbox` 必然不同槽，Lua 做不到。
所以投递可靠性被有意挪到了客户端重试约定上 —— 没收到回执就带同一个 `instructionId` 重试。

### 键命名

所有 Redis key 只有一个生成入口：`KeyNamespace`。
`KeyLiteralGuardTest` 扫描全部生产源码，阻止任何地方手工拼键名 ——
拼了就等于契约有了第二个版本，而两版不一致的表现是"消息投进去了但没人消费"，没有任何报错。

```
{s224}:sess:s-local:inbox        分片 = FNV-1a(sessionId) % 256
{s224}:sess:s-local:outbox       hash tag 让同 session 的 key 同槽，摘牌脚本才可能原子
{s224}:sess:s-local:cursor       双游标 msg / ctrl，刻意不设 TTL
{s224}:sess:s-local:lease
{s224}:sess:s-local:state
{s224}:sess:s-local:ctrl-stream
ready                            刻意不分片：每个 pod 只监听一条 stream
```

**分片数 256 与 hash tag 格式已冻结**，改动等于数据迁移。
分片用 FNV-1a 而非 `String.hashCode()`：后者低位对 `% 256` 分布不均，
且换语言实现时只能靠"照抄 JDK"；FNV-1a 是完全写在代码里的十几行运算。

### 用户消息也落库，客户端不本地回显

协议 v1.1 的核心变更：

```
输入 → 服务端落库（role=USER）→ 进流 → 客户端收到推送 → 才渲染
```

**不本地回显是有意的**：本地回显与流重放是两套顺序来源，只有一套受 `msgSeq` 约束。
去掉之后一个会话里只剩一个 seq 空间 —— 多端登录时各端看到的顺序必然一致，
空窗判定也不会被本地插入的行打乱，重开会话时自己问过的话也在历史里。

代价是多一个来回的感知延迟。状态行用 `⋯ 投递中 「…」` 补偿，
投递失败时会把原文回显出来 —— 否则用户既看不到自己说了什么，也不知道要不要重发。

幂等落在数据库上：`(session_id, instruction_id)` 唯一索引 + `turn_claimed_at` 原子认领。
**先查后分配**：先分配序号再发现是重试，那个序号就烧掉了，序列里留下洞。

| 维度 | 取值 |
|---|---|
| `role` | `USER` / `ASSISTANT` / `SYSTEM` / `UNKNOWN` —— 谁说的 |
| `type` | `TEXT` / `TEXT_DELTA` / `CARD` / … / `UNKNOWN` —— 是什么内容 |

两个维度正交。不把 `USER` 塞进 `type`，是因为用户将来也会发图片和语音，
那时需要的是 `(USER, IMAGE)` 而不是再造一个 `USER_IMAGE`。

两个 `UNKNOWN` 是向前兼容的落地点：未知枚举值落到它而不是抛异常。
少了这一条，服务端一上线新消息类型，所有老客户端就会在反序列化那一刻整条流断掉 ——
`fallbackText` 的降级设计根本走不到。

### 数据源

整个项目取 `DataSource` 只有一个入口：`DataSourceProvider`。

开发期用 `SimpleDataSourceProvider`（自带一个够用的小池子，无第三方依赖）。
**集成进现有 servlet 项目时换成 `DataSourceProvider.of(现有的 DataSource)`** ——
存储层一行不改，也不会在同一个进程里跑出第二个池。
绑死一个 HikariCP 只会在集成时多出一场依赖冲突和"两个池子谁说了算"的争论。

### agent-tui 的分层

副作用只集中在 `terminal` 包，其余全是纯函数，因此可以直接对着断言写测试：

| 包 | 内容 | 纯 |
|---|---|---|
| `state` | 序号三规则、UI 状态、归约器 | ✓ |
| `input` | 斜杠命令解析与处理 | ✓ |
| `render` | 行缓冲、文字稿归约、卡片与状态行渲染 | ✓ |
| `port` | 与后端之间的五个端口 | ✓ |
| `terminal` | JLine 适配 / 逐行适配 | ✗ |

`port` 里的 `Diagnostics` 与 `TraceControl` 是 `/doctor`、`/keys`、`/trace` 的落点：
键的形态属于 Redis 那一层，终端界面不该知道 —— 换 HTTP 门面时被替换掉的正是那两个实现。

`state/SeqRule` 实现的是开发规划 B 节那三条客户端规则（丢弃 / 追加 / 空窗）。
它不是可选优化：服务端建连时全量重放窗口内消息、不保存也不解析客户端位置，
重复与空窗必然发生，这三条是唯一的收敛手段。

---

## 基础设施配置

配置在 [`deploy/`](./deploy/)，Redis 版本锁 `redis:7.0.15-alpine` —— 阿里云只提供
7.0 / 6.0 / 5.0 / 4.0，没有 6.2 这一档，本地用更新的 Redis 会让人不知不觉依赖上生产没有的能力
（比如 `XREADGROUP` 的 `CLAIM` 参数是 8.4 才有的）。

`redis.conf` 里有三项是刻意设的，不要按默认值改回去：

| 配置 | 值 | 为什么 |
|---|---|---|
| `maxmemory-policy` | `noeviction` | 允许淘汰意味着 Redis 可能悄悄删掉一个 lease 或 cursor 且不报错，表现是 session 永久卡死或双跑 |
| `save` / `appendonly` | RDB 关、AOF 开 | 恢复路径只有一条，验收「重启后接着聊」时不用分辨数据来自哪个快照 |
| `notify-keyspace-events` | 空 | 跨节点中断复用 inbox + 轮询，不开 pub/sub，少一条与 lease 绑定的订阅 |

`--create-database` 用 `TEMPLATE template0` + `LC_COLLATE 'C'`：宿主机 glibc 版本与
PG 镜像内建库时的版本一旦不一致，从 `template1` 建库会被 collation 版本校验直接挡下来。
C 排序规则没有版本号，彻底绕开这类漂移。

需要验证 Lua 的效果复制时起从库（对比主从的 stream ID 是否一致）：

```bash
docker compose -f deploy/redis/docker-compose.yml --profile ha up -d
```

---

## 测试

```bash
mvn -q verify        # 单测 + 覆盖率门禁（行覆盖 80%）
```

**集成测试默认跳过**，给定连接串才跑：

```bash
export AGENT_IT_JDBC_URL=jdbc:postgresql://localhost:5432/agent
export AGENT_IT_DB_USER=admin
export AGENT_IT_DB_PASSWORD=...
export AGENT_IT_REDIS_URI=redis://localhost:6379
mvn -q verify
```

它们验证的是 mock 验证不了的东西：`ON CONFLICT ... RETURNING` 的并发原子性、
jsonb 往返保真、`putIfVersion` 的 CAS 语义、多态 `ContentBlock` 的序列化、
并发重试不烧序号，以及链路追踪里那两条只有真 Redis 才成立的断言 ——
ctrl 追踪必须带着 Lua 刚生成的水位、outbox 追踪必须带着条目 id，打了桩这两样都是假的。

不计入覆盖率门禁的是两类：终端适配层与 CLI 外壳（只做 IO 编排，逻辑已下沉），
以及必须有活的 Redis／数据库才能跑的类（由集成测试提供真实保障）。

几个**快照测试**锁的是冻结契约，红了不要改测试：

| 测试 | 锁住什么 |
|---|---|
| `KeyNamespaceTest.Snapshots` | Redis 键名与分片算法 —— 改了等于数据迁移 |
| `KeyLiteralGuardTest` | 没有模块绕过 `KeyNamespace` |
| `WireFormatSnapshotTest` | 协议的 JSON 线格式 |
| `SchemaMigratorTest` | 内置 schema 能被完整解析出五张表 |

`Test/P1/` 是 P1 的测试用例设计稿（六份，约 60 个用例），自动化实现待补。

---

## 待办与偏差

### 待办

| 位置 | 现状 | 何时补 |
|---|---|---|
| 崩溃接管 | 没有 `XAUTOCLAIM`／`XCLAIM JUSTID` 心跳／死 consumer 清理 | **P3。缺了它进程崩在处理中间时令牌会留在 PEL 里没人回收（INV-2b）** |
| 原子摘牌 | 摘牌与"确认 inbox 已空"分两步 | P3。释放瞬间进来的消息可能无人处理（INV-2） |
| 跨节点打断 | `/stop` 明确报错而不是静默无效 | P5。Worker 还不消费 ctrl 游标 |
| 超窗历史补齐 | 空窗时从消息表拉取已实现，`turnStartId` 裁剪保护未做 | P4（INV-6） |
| HTTP 门面 | 无鉴权、无 SSE 线格式 | 接 servlet 时。`Test/P1` 的 API-003/004、SSE-001/003/005/006 依赖它 |
| AgentState 的 CAS | LWW 写入，`version` 列只做计数 | 上游 `AgentStateStore` 接口不接收版本号，表达不了 CAS。防双跑靠 lease（INV-3） |

### 与开发规划的偏差

两处，都需要在评审时确认：

**AgentState 存 PG，不是 Redis。** 规划 B 节把它归在 Redis 一层。
改成 PG 的理由是上游 `AgentStateStore` 是**阻塞接口**（`void save` / `Optional get`），
天然贴合 JDBC；而 `version` 列还能为后续的 CAS 留出位置。
Redis 因此只承担队列与协调结构。已写回规划 B 节修订。

代价是：每轮推理会有阻塞 JDBC 调用落在响应式链路上，整条 agent 流必须
`subscribeOn(boundedElastic)`（INV-7）—— 这条约束往后每加一个 middleware 都要重新确认一次。

**`RemoteFilesystemSpec` 只路由特定前缀。** 这不是偏差，是需要知道的边界：
`MEMORY.md`、`memory/`、`skills/`、`plans/`、`knowledge/`、`agents/<id>/sessions/`
走远端 store（已验证落到 PG 的 `agent_store_item`，命名空间含 `users/<userId>`，
即 `IsolationScope.USER` 生效）；**写在 workspace 根的任意文件仍落本地磁盘，多 pod 下会分叉**。
规划 A 节真正担心的跨 pod 记忆分叉是被覆盖的，但别指望它托管任意文件。

---

## AgentScope Java

上游：<https://github.com/agentscope-ai/agentscope-java>（`io.agentscope`，Apache 2.0，目标 Java 17）

```xml
<dependency>
  <groupId>io.agentscope</groupId>
  <artifactId>agentscope-bom</artifactId>
  <version>2.0.2</version>
  <type>pom</type>
  <scope>import</scope>
</dependency>
```

规划里引用的类都能对上：

| 规划中的说法 | 实际位置 |
|---|---|
| `HarnessAgent` 单例 | `io.agentscope.harness.agent.HarnessAgent` |
| `RedisDistributedStore` | 实现 `io.agentscope.harness.agent.DistributedStore` |
| workspace 方案 B | `filesystem.spec.RemoteFilesystemSpec` + `filesystem.remote.store.BaseStore` + `IsolationScope` |
| `AgentStateStore` 走 CAS 后端 | `io.agentscope.core.state.AgentStateStore`（上游只有 InMemory / JsonFile，PG 版已自研） |
| Middleware | `io.agentscope.core.middleware.MiddlewareBase` |
| `ToolExecutor` 默认 offload | `io.agentscope.core.tool.ToolExecutor` |
| D4 决定不用的沙箱 | `harness.agent.sandbox.*`、`SandboxSnapshotSpec`、`SandboxExecutionGuard` —— 整条链路不引入 |
| ∥ 轨道的 Skill 仓库 | 参照 `harness.agent.skill.WorkspaceSkillRepository` 写 JDBC 版；`SkillPromotionGate` 已内置 |

本项目对上游的封装只有一个接口：`TurnEngine`（`stream` / `interrupt` / `close`）。
换引擎、或为不同租户装配不同引擎时，改动止步于它。

---

## 环境变量

优先级统一是 **命令行 > 环境变量 > 默认值**。之所以每个跨进程参数都配一个环境变量：
默认形态下同一套引擎参数要喂给内嵌 worker，拆开部署时还要喂给 `agent worker` ——
在两条命令上各写一遍、然后指望它们一直一致，是这类系统里最常见的一种配置漂移，
症状是"换了模型只有一半生效"。

### 连接

| 变量 | 对应参数 | 默认值 | 用在 |
|---|---|---|---|
| `AGENT_REDIS_URI` | *(无 —— 没有命令行参数)* | `redis://localhost:6379` | chat · worker · doctor |
| `AGENT_JDBC_URL` | `--jdbc-url` | `jdbc:postgresql://localhost:5432/agent` | 全部 |
| `AGENT_DB_USER` | `--db-user` | `agent` | 全部 |
| `AGENT_DB_PASSWORD` | `--db-password` | 无 | 全部 |
| `AGENT_DB_POOL_SIZE` | `--db-pool-size` | `10` | 全部 |

**`--redis` 没有命令行参数是故意的。** 会话进程与 worker 必须连同一个 Redis，
连错了的表现是"消息发出去了、永远没有回复"，而两边日志都干干净净。
一条 `export` 同时喂给两个进程，比在两条命令上各写一遍要可靠。

### 模型

| 变量 | 对应参数 | 默认值 |
|---|---|---|
| `AGENT_API_KEY` | *(无，刻意)* | 无。也认 `DASHSCOPE_API_KEY` / `OPENAI_API_KEY` |
| `AGENT_PROVIDER` | `--provider` | `auto` |
| `AGENT_MODEL` | `--model` | `qwen-max` |
| `AGENT_BASE_URL` | `--base-url` | 无，走提供者默认端点 |
| `AGENT_ENGINE` | `--engine` | `agentscope` |
| `AGENT_TOOLS` | `--tools` | `none` |
| `AGENT_MAX_ITERS` | `--max-iters` | `20` |
| `AGENT_SYSTEM_PROMPT` | `--system-prompt` | 无 |
| `AGENT_WORKSPACE` | `--workspace` | `~/.agent-cli/workspace` |

**密码与密钥只能走环境变量**：写在命令行上会进 shell 历史，也会出现在 `ps` 的输出里。
所以这两项没有对应的命令行参数，不是漏了。

**没有环境变量的参数**：`--user`、`--session`、`--sole`、`--plain`、`--trace`、
`--capabilities`、`--consumer`、`--concurrency`。它们每次运行都不同，
放进环境变量只会造出"我明明没加 `--plain`，怎么是逐行模式"这种查半天的现场。

> ⚠️ **IDE 里运行要单独设。** IntelliJ 的运行配置**不继承** shell 的 `export`，
> 要在「运行配置 → 环境变量」里填。数据库连不上、模型缺 Key 时的报错都会把
> 每个参数的来源标出来（命令行／环境变量／默认值），一眼能看出是不是没送到。

接 Kimi 这类 OpenAI 兼容端点，除了 Key 还要三个参数：

```bash
export AGENT_API_KEY=sk-...
export AGENT_PROVIDER=openai
export AGENT_BASE_URL=https://api.kimi.com/coding/
export AGENT_MODEL=kimi-for-coding
./bin/agent
```

只想验分布式链路、不想调模型时用 `--engine scripted`（或 `AGENT_ENGINE=scripted`），
它不需要任何 Key。

> ⚠️ **`--sole` 时模型参数要配在 worker 上，不是客户端。**
> 只起客户端时它只负责收发，推理全在外部 worker 进程里 ——
> `--engine`／`--provider`／`--model`／`--base-url`／`AGENT_API_KEY` 配在客户端一律无效。
> 症状是"模型好像没换"，而人极难联想到是配错了进程 ——
> 尤其在默认形态下它们**是**生效的，加一个 `--sole` 就悄悄失效了，
> 所以客户端会在启动时点名这些参数。
>
> ```bash
> ./bin/agent worker --provider openai --base-url https://api.kimi.com/coding/ \
>     --model kimi-for-coding --tools hotel     # 模型配这里
> ./bin/agent --sole                            # 客户端什么模型参数都不用给
> ```

## 环境

| 依赖 | 版本 |
|---|---|
| JDK | 21 |
| Redis | 7.0（必须，见上方说明） |
| PostgreSQL | 14+（实测 18.4） |
| AgentScope Java | 2.0.2 |
| Lettuce | 6.7.1 |
| JLine | 3.26.3 |
| PostgreSQL JDBC | 42.7.7 |

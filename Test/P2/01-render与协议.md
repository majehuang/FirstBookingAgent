# P2 render 与协议测试用例

## 纯函数与确定性

### REN-001　send_hotel_cards 渲染为 CARD

- 优先级：P0；层级：单元测试。
- 步骤：向 `render("send_hotel_cards", completedPayload)` 传入三家已补全酒店。
- 预期：返回一条 `type=CARD, role=ASSISTANT` 的消息草稿；包含非空 `fallbackText` 和完整 payload；不访问数据库、网络、Redis、时钟或随机数。

### REN-002　相同输入逐字节产生相同输出

- 优先级：P0；层级：纯函数回归。
- 步骤：对同一不可变输入调用 100 次并序列化输出。
- 预期：所有结果逐字节一致；字段和 items 顺序稳定。

### REN-003　render 不修改输入 payload

- 优先级：P0；层级：单元测试。
- 步骤：保留输入 Map/List 的深拷贝，调用 render 后比较。
- 预期：原输入未改变；输出内部集合不可通过外部引用修改。

### REN-004　render 不执行 IO

- 优先级：P0；层级：架构测试。
- 步骤：扫描 render 包依赖，并用禁止 IO 的测试替身运行所有分支。
- 预期：不依赖 Repository、HTTP Client、Redis Client、文件系统或 Scheduler；给定输入同步完成。

## CARD 协议

### REN-005　完整酒店卡片协议往返保真

- 优先级：P0；层级：协议契约。
- 步骤：构造三家酒店、价格、评分、说明及 `dataAsOf` 的 CARD，执行 JSON 序列化/反序列化。
- 预期：`msgSeq/replyId/blockId/role/type/fallbackText/payload/createdAt` 全部相等；酒店顺序不变。

### REN-006　CARD fallbackText 不可为空

- 优先级：P0；层级：参数化单元测试。
- 数据：`null`、空字符串、纯空白。
- 预期：构造被拒绝；不能产生老客户端无法显示的空卡片。

### REN-007　payload 深冻结

- 优先级：P0；层级：单元测试。
- 步骤：构造 CARD 后尝试修改顶层 Map、items List 和 item Map。
- 预期：消息中的载荷保持不变。若当前协议只能浅冻结，本用例应失败并推动实现深不可变拷贝。

### REN-008　超长和非法字段按协议拒绝

- 优先级：P1；层级：参数化单元测试。
- 数据：超长 `replyId/blockId/fallbackText`、缺失 type/role/createdAt、非法 payload 类型。
- 预期：在进入持久化前得到明确协议异常，不留下部分消息。

## 能力协商与降级

### REN-009　支持 CARD 的客户端保留结构

- 优先级：P0；层级：能力协商单元测试。
- 步骤：客户端声明支持 `TEXT/TEXT_DELTA/TEXT_END/CARD`。
- 预期：CARD 原样下发，payload 和 type 不变。

### REN-010　不支持 CARD 时降级为 TEXT

- 优先级：P0；层级：能力协商单元测试。
- 步骤：客户端未上报能力或只支持文本。
- 预期：消息变为 TEXT，正文使用 `fallbackText`，结构 payload 被移除；`msgSeq/replyId/role/createdAt` 不变。

### REN-011　未知表达型工具不破坏事件流

- 优先级：P1；层级：表驱动测试。
- 步骤：传入未注册 toolName。
- 预期：按正式冻结策略返回空富消息或文本 fallback；不能抛出导致整个 turn 中断的未处理异常，也不能伪造 CARD。

### REN-012　畸形 payload 有确定的降级行为

- 优先级：P1；层级：参数化测试。
- 数据：null、缺 items、items 非数组、item 非对象、缺数据时间。
- 预期：每种输入都有冻结的确定结果；至少保留用户可读 fallback；不得产生不可序列化对象。


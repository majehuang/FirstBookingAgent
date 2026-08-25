# P2 MessageAdapter 测试用例

## per-reply 状态

### ADP-001　首次文本增量创建 reply reducer

- 优先级：P0；层级：单元测试。
- 步骤：向空 Adapter 输入 reply A 的第一个 `TEXT_DELTA`。
- 预期：只创建 A 的状态；累积文本正确；尚未收到结束事件时保持活跃。

### ADP-002　同 reply、同 block 的文本按顺序累积

- 优先级：P0；层级：单元测试。
- 步骤：依次输入“我推荐”“这三家”。
- 预期：最终文本为“我推荐这三家”，不重复、不缺字、不按长度重排。

### ADP-003　不同 reply 状态隔离

- 优先级：P0；层级：交错事件测试。
- 步骤：按 A1、B1、A2、B2 顺序输入两个 reply 的增量。
- 预期：A 与 B 分别归约为各自文本；状态、blockId 和输出不串扰。

### ADP-004　Adapter 只累积文本

- 优先级：P0；层级：参数化测试。
- 数据：TOOL_CALL、TOOL_RESULT、CARD、IMAGE、AUDIO、SYSTEM、ERROR。
- 预期：这些消息不进入文本缓存；按原类型输出或交给对应 renderer。

## 顺序与 flush

### ADP-005　卡片前先 flush 未完成文本

- 优先级：P0；层级：INV-8 回归。
- 步骤：输入“我推荐这几家”的 delta，未发 TEXT_END 时紧接 CARD。
- 预期：输出顺序严格为完整文本行、CARD；卡片不能插入句子中间。

### ADP-006　工具消息前先 flush 文本

- 优先级：P0；层级：参数化测试。
- 步骤：分别在未结束文本后输入 TOOL_CALL 和 TOOL_RESULT。
- 预期：文本尾巴先落地，再显示工具消息。

### ADP-007　blockId 变化时 flush 前一块

- 优先级：P0；层级：单元测试。
- 步骤：同 reply 输入 block A 的半句，再输入 block B。
- 预期：A 先完整落地；B 使用新缓存；两块不合并。

### ADP-008　TEXT_END 清空当前 block

- 优先级：P0；层级：生命周期测试。
- 步骤：输入多个 delta 后输入对应 TEXT_END。
- 预期：剩余尾巴落地；active block 和 buffer 清空；重复结束不重复输出正文。

### ADP-009　大批交错消息保持输入顺序

- 优先级：P0；层级：压力单元测试。
- 步骤：生成至少 1000 条文本、工具、CARD 和 TEXT_END 的确定序列。
- 预期：输出顺序与参考 reducer 逐条一致；连续运行 20 次无偶发错位。

## 生命周期与异常

### ADP-010　AgentEnd 后销毁 reply reducer

- 优先级：P0；层级：生命周期测试。
- 步骤：创建 reply A，输入 AgentEnd，再查询活跃状态数量。
- 预期：A 的缓存和 reducer 被删除；活跃数量回到基线；后续 reply 不继承 A 的文本。

### ADP-011　失败、取消和关闭同样释放状态

- 优先级：P1；层级：参数化生命周期测试。
- 数据：正常结束、异常结束、订阅取消、连接关闭。
- 预期：所有终止路径都 flush/丢弃到正式策略并销毁 reducer；大量短 reply 后内存不持续增长。


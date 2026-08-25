# P2 表达型工具与 Middleware 测试用例

## send_hotel_cards

### TOOL-001　工具 Schema 可被 AgentScope 注册

- 优先级：P0；层级：契约测试。
- 步骤：加载工具定义并生成函数调用 Schema。
- 预期：名称固定为 `send_hotel_cards`；必填/可选字段、数组上限和描述完整；Schema 可序列化且无循环引用。

### TOOL-002　合法三酒店输入被接受

- 优先级：P0；层级：单元测试。
- 步骤：按冻结 Schema 提交三个酒店引用及必要上下文。
- 预期：工具执行成功；返回值含模型可理解的 `shown` 摘要；不会直接写数据库或 outbox。

### TOOL-003　shown 摘要准确反映已展示内容

- 优先级：P0；层级：表驱动测试。
- 步骤：分别展示 1、3、上限数量酒店。
- 预期：摘要中的数量和名称与实际卡片一致；不包含未展示酒店；可供模型自然继续追问。

### TOOL-004　空列表和超过上限输入被拒绝

- 优先级：P0；层级：参数化测试。
- 预期：返回结构化工具错误或由 Schema 拦截；不进入补全服务，不生成空 CARD。

### TOOL-005　重复酒店引用处理确定

- 优先级：P1；层级：单元测试。
- 步骤：同一酒店 ID 出现两次。
- 预期：按冻结策略去重或拒绝；结果确定且 shown 与最终卡片一致。

### TOOL-006　工具不信任模型提供的完整价格数据

- 优先级：P0；层级：安全/一致性测试。
- 步骤：模型入参夹带伪造 price/rating/dataAsOf。
- 预期：服务端补全字段覆盖或忽略模型值；最终 CARD 只使用可信服务端数据。

### TOOL-007　工具返回值可被模型继续消费

- 优先级：P1；层级：Agent 集成。
- 步骤：工具完成后让脚本化模型生成一句追问。
- 预期：模型能从 shown 知道已经展示的酒店；不重复完整卡片 JSON 到自然语言上下文。

### TOOL-008　工具本身不直接写 outbox

- 优先级：P0；层级：架构测试。
- 步骤：扫描工具依赖和执行一次工具调用。
- 预期：工具包不依赖 Redis/outbox；富消息只经统一 Event→Adapter→OutboxWriter 管道，保持单写入者。

## onActing middleware

### MID-001　从 ActingInput.toolCalls() 读取入参

- 优先级：P0；层级：Middleware 单元测试。
- 步骤：构造包含 `send_hotel_cards` 调用的 ActingInput。
- 预期：读取实际 tool call 入参，不从模型自由文本、工具输出字符串或全局变量重新解析。

### MID-002　只处理匹配的表达型工具

- 优先级：P0；层级：参数化测试。
- 数据：`send_hotel_cards`、普通 `search_hotels`、未知工具、无 tool call。
- 预期：只对注册的表达型工具补全；其他调用原样继续。

### MID-003　服务端补全三个酒店的完整字段

- 优先级：P0；层级：集成测试。
- 步骤：Fake 服务返回三个固定酒店和固定数据时间。
- 预期：metadata 含完整三项、顺序与表达意图一致、所有展示字段和 `dataAsOf` 齐全。

### MID-004　使用 withMetadataEntry 保留原 ActingInput

- 优先级：P0；层级：不可变性测试。
- 步骤：处理带已有 metadata 的 ActingInput。
- 预期：返回新对象；原对象不变；已有 metadata 不丢失；只新增/替换本工具约定的 key。

### MID-005　多个 toolCalls 按声明顺序补全

- 优先级：P1；层级：顺序测试。
- 步骤：一个 ActingInput 含两个表达型调用和一个普通调用。
- 预期：输出关联正确、不串 payload；表达型调用顺序保持不变。

### MID-006　部分酒店不存在时行为确定

- 优先级：P0；层级：故障语义测试。
- 步骤：三个引用中一个查不到。
- 预期：按冻结策略整体失败或生成明确的部分结果；shown、CARD 和数量完全一致，不能声称展示三家却只有两家。

### MID-007　补全服务异常不会产生半成品 CARD

- 优先级：P0；层级：故障注入。
- 步骤：服务端查询超时、返回非法数据或抛异常。
- 预期：按正式策略输出工具错误/文本 fallback；消息表和 outbox 不出现缺价格、缺时间的 CARD。

### MID-008　阻塞补全运行在 boundedElastic

- 优先级：P0；层级：线程模型测试；覆盖 INV-7。
- 步骤：记录补全函数执行线程。
- 预期：不在 Reactor event-loop/parallel 线程执行；显式 offload 到 boundedElastic 或专用阻塞调度器。

### MID-009　慢补全不阻塞其他 session

- 优先级：P0；层级：并发集成。
- 步骤：session A 的补全阻塞 2 秒，同时 session B 处理纯文本。
- 预期：B 持续正常输出；A 的阻塞不占死共享事件循环。

### MID-010　取消订阅时取消未完成补全

- 优先级：P1；层级：Reactor 生命周期测试。
- 步骤：补全等待期间取消 turn。
- 预期：下游订阅释放；不在取消后写 metadata、消息表或 outbox；可取消的查询收到取消信号。

### MID-011　metadata 载荷可序列化

- 优先级：P0；层级：契约测试。
- 步骤：补全真实字段后执行项目统一 JSON 序列化。
- 预期：没有连接、Future、Lazy Proxy 或领域实体泄漏进 payload；结果可作为 jsonb 持久化。

## BlockHound

### BH-001　直接在事件循环执行阻塞补全会被检出

- 优先级：P0；层级：反证测试。
- 步骤：测试夹具故意移除 offload 并执行阻塞调用。
- 预期：BlockHound 稳定抛出阻塞调用错误，证明门禁有效。

### BH-002　生产 middleware 零 BlockHound 告警

- 优先级：P0；层级：集成测试。
- 步骤：安装 BlockHound，运行正常、慢查询和异常补全场景。
- 预期：零告警；所有结果和异常语义保持正确。

### BH-003　MessageAdapter 零阻塞调用

- 优先级：P0；层级：集成/架构测试。
- 步骤：在 event-loop 上处理大批 Adapter 事件。
- 预期：零告警；Adapter 不访问 IO、不调用 `block()`、`sleep()` 或同步 Future.get。

### BH-004　BlockHound 在 Maven 集成测试阶段自动启用

- 优先级：P0；层级：构建测试。
- 步骤：运行 `mvn clean verify` 并检查测试配置。
- 预期：P2 集成测试不依赖开发者手工加 JVM 参数；反证用例在配置失效时能使构建失败。


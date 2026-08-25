package io.agentharness.engine;

import io.agentharness.engine.rich.RichMessageRegistry;
import io.agentharness.engine.store.PostgresAgentStateStore;
import io.agentharness.engine.store.PostgresBaseStore;
import io.agentharness.protocol.SessionRef;
import io.agentharness.store.jdbc.Jdbc;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.harness.agent.DistributedStore;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.spec.RemoteFilesystemSpec;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * HarnessAgent 单例。
 *
 * <p>v2 的 agent 是<b>无状态引擎</b>：一个实例服务所有 {@code (userId, sessionId)}，
 * 状态按需从 store 加载。所以这里没有 Agent Factory、没有 Session Factory、没有实例缓存 ——
 * 那整层抽象在这个模型下是零代码（开发规划 H 节）。
 *
 * <p>装配上的三个决定：
 * <ul>
 *   <li><b>workspace 走 {@link RemoteFilesystemSpec} + PG BaseStore，{@code IsolationScope.USER}。</b>
 *       HarnessAgent 默认开双层长期记忆，用本地文件系统会让记忆按 pod 分叉。
 *       <br>注意这个 spec<b>只路由特定前缀</b>到远端 store：{@code MEMORY.md}、{@code memory/}、
 *       {@code skills/}、{@code plans/}、{@code knowledge/}、{@code agents/<id>/sessions/} 等。
 *       写在 workspace 根的任意文件仍然落本地磁盘，多 pod 下会分叉 ——
 *       这也是文件系统工具默认关闭的另一个理由。</li>
 *   <li><b>不引入沙箱</b>（D4）。本期需求不含不可信代码执行，
 *       {@code SandboxSnapshotSpec} / {@code SandboxExecutionGuard} 整条链路都不配。</li>
 *   <li><b>默认关掉 shell 与文件系统工具。</b>没有沙箱时它们都直接作用在 pod 上。
 *       文件系统工具实测可以经 {@code ../} 穿越出 workspace 一直列到宿主机根目录 ——
 *       模型的输出由用户输入驱动，等于把一个信息泄露面交给了不可控的一方。
 *       两个开关都默认关，要开必须显式开。</li>
 * </ul>
 */
public final class AgentScopeEngine implements TurnEngine {

    private final HarnessAgent agent;
    private final StreamOptions streamOptions;
    private final ToolBundle tools;
    private final String modelName;

    private AgentScopeEngine(HarnessAgent agent, StreamOptions streamOptions, ToolBundle tools,
                             String modelName) {
        this.agent = agent;
        this.streamOptions = streamOptions;
        this.tools = tools;
        this.modelName = modelName;
    }

    @Override
    public String engineName() {
        return modelName;
    }

    public static AgentScopeEngine create(EngineConfig config, Jdbc jdbc) {
        return create(config, jdbc, ToolBundle.empty());
    }

    public static AgentScopeEngine create(EngineConfig config, Jdbc jdbc, ToolBundle tools) {
        DistributedStore distributedStore = DistributedStore.builder()
                .agentStateStore(new PostgresAgentStateStore(jdbc))
                .baseStore(new PostgresBaseStore(jdbc))
                .build();

        RemoteFilesystemSpec filesystem = new RemoteFilesystemSpec(distributedStore.baseStore())
                .isolationScope(IsolationScope.USER);

        HarnessAgent.Builder builder = HarnessAgent.builder()
                .name("agent")
                .model(ModelFactory.create(config))
                .distributedStore(distributedStore)
                .filesystem(filesystem)
                .maxIters(config.maxIters());

        if (config.systemPrompt() != null && !config.systemPrompt().isBlank()) {
            builder.sysPrompt(config.systemPrompt());
        }
        if (config.workspace() != null) {
            builder.workspace(config.workspace());
        }
        if (config.disableShellTool()) {
            builder.disableShellTool();
        }
        if (config.disableFilesystemTools()) {
            builder.disableFilesystemTools();
        }
        if (tools.hasToolkit()) {
            builder.toolkit(tools.toolkit());
        }
        if (!tools.middlewares().isEmpty()) {
            builder.middlewares(tools.middlewares());
        }

        return new AgentScopeEngine(builder.build(), defaultStreamOptions(), tools,
                config.modelName());
    }

    /**
     * 流式选项。
     *
     * <p>{@code incremental(true)} 是前提：关掉之后每个事件带的是累计文本，
     * 客户端会看到内容一遍遍重复。{@link EventMapper} 的映射也建立在增量语义上。
     */
    private static StreamOptions defaultStreamOptions() {
        return StreamOptions.builder()
                .eventTypes(EventType.REASONING, EventType.TOOL_RESULT,
                        EventType.HINT, EventType.AGENT_RESULT)
                .incremental(true)
                .includeReasoningChunk(true)
                .includeReasoningResult(false)
                // 关掉工具入参的分片流：开着的话每个参数片段都会到达一次，
                // 用户会看到 ⚒ 刷一屏（分片块的工具名是占位符 __fragment__）。
                // 我们只需要完整的那一次调用。
                .includeActingChunk(false)
                .build();
    }

    /**
     * 跑一轮推理。
     *
     * <p><b>调用方必须把返回的流 {@code subscribeOn(boundedElastic)}。</b>
     * {@code AgentStateStore} 是阻塞接口，配 PG 之后每轮推理都会有阻塞 JDBC 调用
     * 落在订阅线程上；落在事件循环线程上就会占死全 pod 的所有 session（INV-7）。
     */
    @Override
    public Flux<Event> stream(SessionRef session, String text) {
        Msg message = Msg.builder()
                .role(MsgRole.USER)
                .textContent(text)
                .build();

        RuntimeContext context = RuntimeContext.builder()
                .userId(session.userId())
                .sessionId(session.sessionId())
                .build();

        return agent.stream(List.of(message), streamOptions, context);
    }

    /**
     * 打断当前推理。
     *
     * <p>只对<b>本进程内</b>正在跑的 turn 有效。跨节点打断要靠把 IMMEDIATE 指令写进 inbox，
     * 由持牌 pod 轮询扫到后调到这里（P5）。
     */
    @Override
    public void interrupt() {
        agent.interrupt();
    }

    @Override
    public RichMessageRegistry renderers() {
        return tools.renderers();
    }

    public HarnessAgent agent() {
        return agent;
    }

    @Override
    public void close() {
        agent.close();
    }
}

package io.agentharness.cli;

import io.agentharness.engine.AgentScopeBackend;
import io.agentharness.cli.redis.RedisBackend;
import io.agentharness.comm.egress.RedisMessageSubscriber;
import io.agentharness.comm.ingress.RedisInstructionPublisher;
import io.agentharness.engine.AgentScopeEngine;
import io.agentharness.protocol.ClientCapabilities;
import io.agentharness.protocol.SessionRef;
import io.agentharness.store.datasource.DataSourceProvider;
import io.agentharness.store.jdbc.Jdbc;
import io.agentharness.redis.RedisConfig;
import io.agentharness.redis.RedisRuntime;
import io.agentharness.store.message.MessageRepository;
import io.agentharness.store.message.PostgresMessageRepository;
import io.agentharness.tui.TuiApp;
import io.agentharness.tui.TuiConfig;
import io.agentharness.tui.loopback.LoopbackBackend;
import io.agentharness.tui.port.AgentBackend;
import io.agentharness.tui.terminal.TerminalUi;
import io.agentharness.tui.terminal.TerminalUiFactory;
import io.agentharness.trace.TraceSink;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 会话模式的参数与启动逻辑。
 *
 * <p><b>这是默认模式</b>：{@code agent} 不带子命令时直接进入会话，
 * 不需要再敲一个 {@code chat}。会话内部的操作走斜杠命令（{@code /new}、{@code /stop}、
 * {@code /status} …），子命令留给那些真正独立的进程（{@code worker}、{@code doctor} …）。
 *
 * <p>非 tty 环境（管道、CI）会自动降级为逐行模式，输出是可 diff 的纯文本，
 * 因此同一条命令既能给人用，也能写进验收脚本。
 */
public final class ChatOptions {

    /** 客户端声明的渲染能力。 */
    enum Capabilities {
        /** 支持全部消息类型。 */
        FULL,
        /** 只保证文本 —— 富消息会被服务端压成 fallbackText。 */
        TEXT
    }

    enum Backend {
        /** 本地假引擎，零依赖，用来验证 TUI 本身。 */
        LOOPBACK,
        /** 真实 AgentScope 引擎 + PostgreSQL 消息表，全部在本进程内。 */
        AGENTSCOPE,
        /**
         * 经 Redis 与 Worker 进程通信。
         *
         * <p>这是最终形态：投递走 inbox+ready，输出走 outbox，
         * 本进程只当客户端。需要另起 {@code agent worker}。
         */
        REDIS
    }

    @Option(names = {"-u", "--user"}, description = "用户 id，企业内部即员工 id（默认 ${DEFAULT-VALUE}）")
    private String user = "dev";

    @Option(names = {"-s", "--session"}, description = "会话 id（默认 ${DEFAULT-VALUE}）")
    private String session = "s-local";

    @Option(names = {"-b", "--backend"}, description = "后端：${COMPLETION-CANDIDATES}（默认 ${DEFAULT-VALUE}）")
    private Backend backend = Backend.LOOPBACK;

    @Option(names = "--plain", description = "强制逐行模式，不使用交互式终端")
    private boolean plain;

    @Option(names = {"-r", "--redis"}, description = "Redis 连接串（默认 ${DEFAULT-VALUE}）")
    private String redisUri = "redis://localhost:6379";

    @Option(names = "--capabilities",
            description = "客户端能力：${COMPLETION-CANDIDATES}（默认 ${DEFAULT-VALUE}）。"
                    + "TEXT 用来验证服务端降级 —— 卡片会被压成纯文本")
    private Capabilities capabilities = Capabilities.FULL;

    // ---- 假引擎 ----

    @Option(names = "--token-delay-ms", description = "假引擎的吐字间隔（默认 ${DEFAULT-VALUE}）")
    private long tokenDelayMs = 28;

    @Option(names = "--tool-delay-ms", description = "假引擎的工具耗时（默认 ${DEFAULT-VALUE}）")
    private long toolDelayMs = 700;

    // ---- 真引擎 ----

    @Mixin
    private EngineOptions engineOptions = new EngineOptions();

    @Mixin
    private DbOptions db = new DbOptions();

    /** 进入会话，跑到用户退出为止。返回进程退出码。 */
    Integer run() {
        SessionRef sessionRef = SessionRef.of(user, session);
        TuiConfig config = TuiConfig.of(sessionRef).withForcePlain(plain);
        List<AutoCloseable> resources = new ArrayList<>();

        try {
            AgentBackend agentBackend = openBackend(resources);
            try (TerminalUi ui = TerminalUiFactory.open(config.historyFile(), config.forcePlain());
                 TuiApp app = new TuiApp(ui, agentBackend, config)) {
                return app.run();
            }
        } catch (IllegalStateException e) {
            System.err.println("✗ " + e.getMessage());
            return 3;
        } catch (Exception e) {
            System.err.println("✗ 会话异常退出：" + rootMessage(e));
            return 1;
        } finally {
            closeAll(resources);
        }
    }

    private AgentBackend openBackend(List<AutoCloseable> resources) {
        return switch (backend) {
            case LOOPBACK -> new LoopbackBackend(
                    Duration.ofMillis(tokenDelayMs), Duration.ofMillis(toolDelayMs));
            case AGENTSCOPE -> openAgentScope(resources);
            case REDIS -> openRedis(resources);
        };
    }

    private AgentBackend openAgentScope(List<AutoCloseable> resources) {
        DataSourceProvider provider0 = db.openProvider();
        resources.add(provider0);

        Jdbc jdbc = new Jdbc(provider0);
        AgentScopeEngine engine = engineOptions.createAgentScopeEngine(jdbc);
        return new AgentScopeBackend(engine, new PostgresMessageRepository(jdbc),
                "agentscope/" + engineOptions.modelName());
    }

    private AgentBackend openRedis(List<AutoCloseable> resources) {
        DataSourceProvider dataSource = db.openProvider();
        resources.add(dataSource);
        RedisRuntime runtime = RedisRuntime.open(RedisConfig.of(redisUri));
        resources.add(runtime);

        MessageRepository repository = new PostgresMessageRepository(new Jdbc(dataSource));
        ClientCapabilities declared = capabilities == Capabilities.TEXT
                ? ClientCapabilities.defaults()
                : ClientCapabilities.full();

        // 逐行模式本就是排查与验收场景，追踪跟着它走，不再单开一个开关。
        // 打到 stderr 而非 stdout —— stdout 是可 diff 的验收产物，掺进追踪就没法比对了
        TraceSink traceSink = plain ? TraceSink.toStderr("tui") : TraceSink.disabled();

        return new RedisBackend(
                new RedisInstructionPublisher(runtime, repository, traceSink),
                new RedisMessageSubscriber(runtime, declared),
                repository,
                declared);
    }

    private static void closeAll(List<AutoCloseable> resources) {
        for (int i = resources.size() - 1; i >= 0; i--) {
            try {
                resources.get(i).close();
            } catch (Exception e) {
                System.err.println("关闭资源失败：" + e.getMessage());
            }
        }
    }

    private static String rootMessage(Throwable error) {
        Throwable cursor = error;
        while (cursor.getCause() != null && cursor.getCause() != cursor) {
            cursor = cursor.getCause();
        }
        return cursor.getMessage() == null ? cursor.getClass().getSimpleName() : cursor.getMessage();
    }
}

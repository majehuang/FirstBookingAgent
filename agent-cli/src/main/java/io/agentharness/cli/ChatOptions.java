package io.agentharness.cli;

import io.agentharness.cli.redis.RedisBackend;
import io.agentharness.comm.egress.RedisMessageSubscriber;
import io.agentharness.comm.ingress.RedisInstructionPublisher;
import io.agentharness.engine.TurnEngine;
import io.agentharness.protocol.ClientCapabilities;
import io.agentharness.protocol.SessionRef;
import io.agentharness.redis.RedisConfig;
import io.agentharness.redis.RedisRuntime;
import io.agentharness.store.datasource.DataSourceProvider;
import io.agentharness.store.jdbc.Jdbc;
import io.agentharness.store.message.MessageRepository;
import io.agentharness.store.message.PostgresMessageRepository;
import io.agentharness.trace.ToggleTraceSink;
import io.agentharness.tui.TuiApp;
import io.agentharness.tui.TuiConfig;
import io.agentharness.tui.port.AgentBackend;
import io.agentharness.tui.terminal.TerminalUi;
import io.agentharness.tui.terminal.TerminalUiFactory;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

import java.util.ArrayList;
import java.util.List;

/**
 * 会话模式的参数与启动逻辑。这是默认模式：{@code agent} 不带子命令时直接进来。
 *
 * <p><b>默认在同一个进程里跑两半</b>：TUI 客户端 + 一个内嵌 worker。
 * 两半之间照样只经由 Redis 交换数据 —— 同进程只是省去了开第二个终端，
 * 并没有多出一条捷径。这一点很重要：如果内嵌形态走的是进程内直连，
 * 那单进程能跑通就完全不能说明分布式形态能跑通，而那才是要交付的形态。
 *
 * <p>{@code --sole} 只起客户端那一半，推理交给外部的 {@code agent worker}。
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

    @Option(names = {"-u", "--user"}, description = "用户 id，企业内部即员工 id（默认 ${DEFAULT-VALUE}）")
    private String user = "dev";

    @Option(names = {"-s", "--session"}, description = "会话 id（默认 ${DEFAULT-VALUE}）")
    private String session = "s-local";

    /**
     * 只起客户端那一半。
     *
     * <p>用在两种场合：worker 已经单独起在别处（多节点的真实形态），
     * 或者要验证"客户端确实没有走捷径"—— 不起 worker 时消息应当停在 inbox 里没人处理，
     * 而不是凭空得到回复。
     */
    @Option(names = "--sole",
            description = "不启动内嵌 worker，只当客户端。推理交给外部的 agent worker")
    private boolean sole;

    @Option(names = "--plain", description = "强制逐行模式，不使用交互式终端")
    private boolean plain;

    @Option(names = "--trace",
            description = "启动即开启链路追踪，输出到 stderr。逐行模式默认开启。"
                    + "会话中可用 /trace on|off 随时开关")
    private boolean trace;

    @Option(names = "--capabilities",
            description = "客户端能力：${COMPLETION-CANDIDATES}（默认 ${DEFAULT-VALUE}）。"
                    + "TEXT 用来验证服务端降级 —— 卡片会被压成纯文本")
    private Capabilities capabilities = Capabilities.FULL;

    @Mixin
    private EngineOptions engineOptions = new EngineOptions();

    @Mixin
    private DbOptions db = new DbOptions();

    /** 进入会话，跑到用户退出为止。返回进程退出码。 */
    Integer run() {
        SessionRef sessionRef = SessionRef.of(user, session);
        List<AutoCloseable> resources = new ArrayList<>();

        try {
            // 依赖一次性备齐，任何一个不行就当场失败。
            // 半死不活地起来（界面正常、能打字、然后每个动作各报一次错）比直接失败难查得多
            DataSourceProvider dataSource = db.openVerifiedProvider();
            resources.add(dataSource);
            Jdbc jdbc = new Jdbc(dataSource);
            MessageRepository repository = new PostgresMessageRepository(jdbc);

            String redisUri = RedisEndpoint.resolve();
            RedisRuntime runtime = RedisRuntime.open(RedisConfig.of(redisUri));
            resources.add(runtime);

            // 打到 stderr 而非 stdout —— 逐行模式的 stdout 是可 diff 的验收产物，
            // 掺进追踪就没法比对了
            ToggleTraceSink clientTrace = ToggleTraceSink.toStderr("tui", tracing());
            List<ToggleTraceSink> switches = new ArrayList<>();
            switches.add(clientTrace);

            List<String> notes = new ArrayList<>();
            if (sole) {
                warnIgnoredEngineOptions();
                notes.add("只起了客户端。没有 worker 的话消息会停在 inbox 里 —— 另开一个终端跑 agent worker");
            } else {
                ToggleTraceSink workerTrace = ToggleTraceSink.toStderr("worker", tracing());
                switches.add(workerTrace);

                // 引擎在 TUI 之前建：缺 Key 要在界面起来之前失败，
                // 否则那条诊断会被终端的全屏渲染盖掉
                TurnEngine engine = engineOptions.createEngine(jdbc);
                resources.add(engine);

                WorkerRuntime worker = WorkerRuntime.embedded(
                        runtime, jdbc, repository, engine, workerTrace);
                resources.add(worker);

                notes.add("内嵌 worker 已启动  consumer=" + worker.consumerName()
                        + "  引擎=" + engine.engineName());
                // 内嵌 worker 消费的是全局 ready 流，不只是你这个会话。
                // 单机开发无所谓，但同一个 Redis 上有别人时，
                // 你的进程会用你的模型参数去跑别人的会话 —— 这事必须写在脸上
                notes.add("它消费的是全局唤醒队列，同一个 Redis 上的其它会话也可能落到这里");
            }

            AgentBackend backend = new RedisBackend(
                    new RedisInstructionPublisher(runtime, repository, clientTrace),
                    new RedisMessageSubscriber(runtime, declaredCapabilities()),
                    repository,
                    declaredCapabilities(),
                    sole ? "redis" : "redis · 内嵌 worker",
                    new SessionDiagnostics(redisUri, dataSource, db.resolveJdbcUrl()),
                    new TraceSwitch(switches));

            TuiConfig config = TuiConfig.of(sessionRef).withForcePlain(plain).withNotes(notes);
            try (TerminalUi ui = TerminalUiFactory.open(config.historyFile(), config.forcePlain());
                 TuiApp app = new TuiApp(ui, backend, config)) {
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

    /**
     * 逐行模式本就是排查与验收场景，追踪跟着它走；交互式终端下要显式开。
     *
     * <p>{@code --trace} 与 worker 侧同名同义，两个进程一套用法。
     */
    private boolean tracing() {
        return plain || trace;
    }

    private ClientCapabilities declaredCapabilities() {
        return capabilities == Capabilities.TEXT
                ? ClientCapabilities.defaults()
                : ClientCapabilities.full();
    }

    /**
     * {@code --sole} 下引擎参数不生效，要提前说出来。
     *
     * <p>症状是"模型好像没换"，而人极难联想到是配错了进程 ——
     * 尤其在默认形态下它们<b>是</b>生效的，加一个 {@code --sole} 就悄悄失效了。
     */
    private void warnIgnoredEngineOptions() {
        List<String> ignored = engineOptions.optionsIgnoredWithoutWorker();
        if (!ignored.isEmpty()) {
            System.err.println("· " + String.join("、", ignored)
                    + " 在 --sole 下不生效：推理由外部 worker 进程执行。"
                    + "这些参数要加在 agent worker 上。");
        }
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

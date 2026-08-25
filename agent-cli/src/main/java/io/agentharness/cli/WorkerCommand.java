package io.agentharness.cli;

import io.agentharness.engine.TurnEngine;
import io.agentharness.redis.RedisConfig;
import io.agentharness.redis.RedisRuntime;
import io.agentharness.store.datasource.DataSourceProvider;
import io.agentharness.store.jdbc.Jdbc;
import io.agentharness.store.message.PostgresMessageRepository;
import io.agentharness.trace.TraceSink;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;

/**
 * 任务层进程：消费 ready、抢执行权、抽干 inbox、跑推理、写 outbox。
 *
 * <p>与会话模式<b>没有任何直接调用</b>，只经由 Redis 交换数据 ——
 * 这是通信层与任务层可以独立部署、独立扩容的前提，也是整套设计最主要的收益。
 * 多开几个 worker 进程即可水平扩展，但<b>崩溃接管要等 P3</b>
 * （没有 XAUTOCLAIM，进程崩在处理中间时那个令牌会留在 PEL 里没人回收）。
 */
@Command(name = "worker", mixinStandardHelpOptions = true,
        description = "Worker 模块：消费 ready、抢执行权、抽干 inbox、写 outbox")
public final class WorkerCommand implements Callable<Integer> {

    /**
     * 消费者名。
     *
     * <p><b>同时存活的实例绝不能重名</b>：消费组按消费者名分配令牌，
     * 重名的两个进程会互相抢消息、PEL 也会混在一起，症状是
     * "消息有时没人处理"或"处理了两次"，而现场看不出有第二个进程。
     *
     * <p>想过在启动时自动探测重名，但 {@code XINFO CONSUMERS} 的 {@code idle}
     * 在目标 Redis 上反映的是"上次成功取到消息"而非"上次发起请求" ——
     * 一个活着但闲着的实例照样显示 idle 很大，据此判活会漏报。
     * 可靠的做法要靠心跳键，那属于 P3（连同 XAUTOCLAIM 与死 consumer 清理一起做）。
     * 在那之前，本机多开时请显式 {@code --consumer} 区分。
     */
    @Option(names = "--consumer",
            description = "消费者名，同时存活的实例不能重名（默认取主机名）。"
                    + "本机多开时务必显式区分")
    private String consumerName = WorkerRuntime.standaloneConsumerName();

    @Option(names = "--concurrency",
            description = "同时在飞的 session 数上限（默认 ${DEFAULT-VALUE}）")
    private int concurrency = WorkerRuntime.STANDALONE_CONCURRENCY;

    @Option(names = "--trace",
            description = "把链路各环节打到 stderr：抢到 ready、turn 启动、每个 step 事件、"
                    + "进 ctrl-stream 与 outbox 的原始载荷。"
                    + "与客户端的 --plain 按 sessionId 对齐成一条完整链路")
    private boolean trace;

    @Mixin
    private DbOptions db = new DbOptions();

    @Mixin
    private EngineOptions engineOptions = new EngineOptions();

    @Override
    public Integer call() {
        List<AutoCloseable> resources = new ArrayList<>();
        try {
            DataSourceProvider dataSource = db.openVerifiedProvider();
            resources.add(dataSource);
            Jdbc jdbc = new Jdbc(dataSource);

            String redisUri = RedisEndpoint.resolve();
            RedisRuntime runtime = RedisRuntime.open(RedisConfig.of(redisUri));
            resources.add(runtime);

            TurnEngine engine = engineOptions.createEngine(jdbc);
            resources.add(engine);

            // 六个环节里有五个在本进程，同一个 sink 串起来即可；
            // 客户端那一环（写 inbox）在会话进程里，靠 sessionId 对齐
            TraceSink traceSink = trace ? TraceSink.toStderr("worker") : TraceSink.disabled();

            WorkerRuntime worker = WorkerRuntime.standalone(
                    runtime, jdbc, new PostgresMessageRepository(jdbc), engine, traceSink,
                    consumerName, concurrency);
            resources.add(worker);

            System.out.println("worker 已就绪  consumer=" + worker.consumerName()
                    + "  引擎=" + engine.engineName()
                    + "  并发=" + worker.concurrency());
            System.out.println("Redis " + RedisEndpoint.describe() + "  ·  " + db.resolveJdbcUrl());
            if (trace) {
                System.out.println("链路追踪已开启，输出在 stderr。");
            }
            // worker 只跑推理，不带聊天界面。不写这句的话，
            // 本终端看起来就像一个等着你说话的提示符 —— 敲进来的字会被 shell 回显，
            // 于是"发了消息却没有回复"，而两边的日志都干干净净
            System.out.println();
            System.out.println("本终端不接受输入，每完成一轮打一行。另开一个终端进会话：");
            System.out.println("    ./bin/agent --sole" + (trace ? " --trace" : ""));
            System.out.println("按 Ctrl+C 停止 worker。");
            System.out.println();

            park();
            return 0;
        } catch (RuntimeException e) {
            System.err.println("✗ worker 启动失败：" + rootMessage(e));
            return 1;
        } finally {
            closeAll(resources);
        }
    }

    /** 挂起主线程直到收到停止信号。调度循环跑在 Reactor 的调度器上。 */
    private static void park() {
        CountDownLatch stopped = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(stopped::countDown, "worker-shutdown"));
        try {
            stopped.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
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

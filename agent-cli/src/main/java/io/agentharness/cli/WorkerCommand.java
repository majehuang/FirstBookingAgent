package io.agentharness.cli;

import io.agentharness.engine.TurnEngine;
import io.agentharness.redis.RedisConfig;
import io.agentharness.redis.RedisRuntime;
import io.agentharness.store.datasource.DataSourceProvider;
import io.agentharness.store.eventlog.PostgresEventLogRepository;
import io.agentharness.store.jdbc.Jdbc;
import io.agentharness.store.message.MessageRepository;
import io.agentharness.store.message.PostgresMessageRepository;
import io.agentharness.task.coldstore.ColdStorageBypass;
import io.agentharness.task.dispatch.ReadyDispatcher;
import io.agentharness.task.outbox.OutboxStream;
import io.agentharness.task.outbox.OutboxWriter;
import io.agentharness.task.worker.ControlPublisher;
import io.agentharness.task.worker.SessionWorker;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

import java.net.InetAddress;
import java.net.UnknownHostException;
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

    @Option(names = {"-r", "--redis"}, description = "Redis 连接串（默认 ${DEFAULT-VALUE}）")
    private String redisUri = "redis://localhost:6379";

    @Option(names = "--consumer",
            description = "消费者名，同时存活的实例不能重名（默认取主机名）")
    private String consumerName = defaultConsumerName();

    @Option(names = "--concurrency",
            description = "同时在飞的 session 数上限（默认 ${DEFAULT-VALUE}）")
    private int concurrency = 8;

    @Mixin
    private DbOptions db = new DbOptions();

    @Mixin
    private EngineOptions engineOptions = new EngineOptions();

    @Override
    public Integer call() {
        List<AutoCloseable> resources = new ArrayList<>();
        try {
            DataSourceProvider dataSource = db.openProvider();
            resources.add(dataSource);
            Jdbc jdbc = new Jdbc(dataSource);

            RedisRuntime runtime = RedisRuntime.open(RedisConfig.of(redisUri));
            resources.add(runtime);

            TurnEngine engine = engineOptions.createEngine(jdbc);
            resources.add(engine);

            MessageRepository repository = new PostgresMessageRepository(jdbc);
            OutboxStream outbox = new OutboxStream(runtime);

            SessionWorker worker = new SessionWorker(
                    runtime,
                    repository,
                    engine,
                    new OutboxWriter(repository, outbox),
                    outbox,
                    new ControlPublisher(runtime),
                    new ColdStorageBypass(new PostgresEventLogRepository(jdbc)));

            ReadyDispatcher dispatcher = new ReadyDispatcher(
                    runtime, worker, consumerName, concurrency, java.time.Duration.ofMillis(50));
            resources.add(dispatcher);

            dispatcher.start().block();

            System.out.println("worker 已就绪  consumer=" + consumerName
                    + "  引擎=" + engineOptions.modelName()
                    + "  并发=" + concurrency);
            System.out.println("Redis " + redisUri + "  ·  " + db.jdbcUrl);
            System.out.println("按 Ctrl+C 停止。");

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

    /**
     * 默认取主机名。
     *
     * <p>刻意<b>不</b>加时间戳后缀：同时存活的实例本就不会重名，
     * 加了只会让消费组里的死 consumer 堆积得更快（P3 要清理它们）。
     */
    private static String defaultConsumerName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "worker-" + ProcessHandle.current().pid();
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

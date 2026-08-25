package io.agentharness.cli;

import io.agentharness.engine.TurnEngine;
import io.agentharness.redis.RedisRuntime;
import io.agentharness.store.eventlog.PostgresEventLogRepository;
import io.agentharness.store.jdbc.Jdbc;
import io.agentharness.store.message.MessageRepository;
import io.agentharness.task.coldstore.ColdStorageBypass;
import io.agentharness.task.dispatch.ReadyDispatcher;
import io.agentharness.task.outbox.OutboxStream;
import io.agentharness.task.outbox.OutboxWriter;
import io.agentharness.task.worker.ControlPublisher;
import io.agentharness.task.worker.SessionWorker;
import io.agentharness.task.worker.TurnLog;
import io.agentharness.trace.TraceSink;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;

/**
 * 任务层的装配。
 *
 * <p>会话进程与 {@code agent worker} 共用这一份 —— 两处各装配一遍的话，
 * 迟早会在某个参数上分叉，而分叉的表现是"内嵌能跑、独立起不来"（或者反过来），
 * 查起来要先意识到有两套代码。
 *
 * <p>两种形态的差别只有三个参数，全在 {@link #embedded} 与 {@link #standalone} 里写死：
 * <table border="1">
 *   <caption>两种形态</caption>
 *   <tr><th></th><th>consumer 名</th><th>并发</th><th>每轮日志</th></tr>
 *   <tr><td>内嵌</td><td>主机名-pid</td><td>1</td><td>不打</td></tr>
 *   <tr><td>独立</td><td>主机名（可 --consumer 覆盖）</td><td>8</td><td>打到 stdout</td></tr>
 * </table>
 */
public final class WorkerRuntime implements AutoCloseable {

    /** 与 {@code OutboxStream} / {@code ControlPublisher} 的默认值一致，此处只是为了能传 sink。 */
    private static final long DEFAULT_OUTBOX_MAX_LEN = 10_000L;
    private static final Duration CONTROL_TTL = Duration.ofHours(12);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(50);

    static final int EMBEDDED_CONCURRENCY = 1;
    static final int STANDALONE_CONCURRENCY = 8;

    private final ReadyDispatcher dispatcher;
    private final String consumerName;
    private final int concurrency;

    private WorkerRuntime(ReadyDispatcher dispatcher, String consumerName, int concurrency) {
        this.dispatcher = dispatcher;
        this.consumerName = consumerName;
        this.concurrency = concurrency;
    }

    /**
     * 跟着会话进程一起跑的 worker。
     *
     * <p>并发定死 1：内嵌形态服务的是一个人的一个终端，
     * 开到 8 只会让这个进程在你看不见的地方替别人跑推理（见下面 consumer 名那段）。
     *
     * <p>每轮日志<b>不打</b>：它会和回复内容一起进滚动区，把正文冲掉。
     * 要看链路用 {@code /trace}。
     */
    static WorkerRuntime embedded(RedisRuntime runtime, Jdbc jdbc, MessageRepository repository,
                                  TurnEngine engine, TraceSink trace) {
        return start(runtime, jdbc, repository, engine, trace, TurnLog.disabled(),
                embeddedConsumerName(), EMBEDDED_CONCURRENCY);
    }

    /** {@code agent worker}：独立进程，日志打到 stdout。 */
    static WorkerRuntime standalone(RedisRuntime runtime, Jdbc jdbc, MessageRepository repository,
                                    TurnEngine engine, TraceSink trace, String consumerName,
                                    int concurrency) {
        return start(runtime, jdbc, repository, engine, trace, TurnLog.toStdout(),
                consumerName, concurrency);
    }

    private static WorkerRuntime start(RedisRuntime runtime, Jdbc jdbc,
                                       MessageRepository repository, TurnEngine engine,
                                       TraceSink trace, TurnLog turnLog,
                                       String consumerName, int concurrency) {
        OutboxStream outbox = new OutboxStream(runtime, DEFAULT_OUTBOX_MAX_LEN, trace);
        SessionWorker worker = new SessionWorker(
                runtime,
                repository,
                engine,
                new OutboxWriter(repository, outbox),
                outbox,
                new ControlPublisher(runtime, CONTROL_TTL, trace),
                new ColdStorageBypass(new PostgresEventLogRepository(jdbc)),
                trace,
                turnLog);

        ReadyDispatcher dispatcher = new ReadyDispatcher(
                runtime, worker, consumerName, concurrency, POLL_INTERVAL);
        dispatcher.start().block();
        return new WorkerRuntime(dispatcher, consumerName, concurrency);
    }

    String consumerName() {
        return consumerName;
    }

    int concurrency() {
        return concurrency;
    }

    @Override
    public void close() {
        dispatcher.close();
    }

    /**
     * 独立 worker 的 consumer 名：主机名。
     *
     * <p>刻意不加后缀 —— 同时存活的实例本就不该重名，加了只会让消费组里的
     * 死 consumer 堆积得更快（P3 要清理它们）。
     */
    static String standaloneConsumerName() {
        return hostname();
    }

    /**
     * 内嵌 worker 的 consumer 名：<b>主机名-pid</b>。
     *
     * <p>必须带 pid。消费组按 consumer 名分配令牌，同一台机器上开两个会话就会重名，
     * 而重名的两个消费者会互相抢消息、PEL 也混在一起 —— 症状是
     * "消息有时没人处理"或"处理了两次"，且现场看不出有第二个进程。
     * 独立 worker 可以靠 {@code --consumer} 人工区分，内嵌的没人会想到去区分。
     */
    static String embeddedConsumerName() {
        return hostname() + "-" + ProcessHandle.current().pid();
    }

    private static String hostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "worker-" + ProcessHandle.current().pid();
        }
    }
}

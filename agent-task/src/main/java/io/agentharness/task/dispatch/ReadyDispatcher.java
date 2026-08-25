package io.agentharness.task.dispatch;

import io.agentharness.keys.KeyNamespace;
import io.agentharness.redis.ReadyToken;
import io.agentharness.redis.RedisRuntime;
import io.agentharness.redis.StreamPayload;
import io.agentharness.task.worker.SessionWorker;
import io.lettuce.core.Consumer;
import io.lettuce.core.StreamMessage;
import io.lettuce.core.XGroupCreateArgs;
import io.lettuce.core.XReadArgs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * 任务控制模块：消费 ready，为每个令牌拉起一个 Worker。
 *
 * <p><b>这是全项目唯一允许使用 {@code flatMap} 的地方（INV-8）。</b>
 * 这里需要的正是它的并发语义：不同 session 必须能并行推进，
 * 一个 session 的慢 turn 不该挡住其它 session。事件管道则一律 {@code concatMap} ——
 * 那里需要的是顺序，用错就会消息错位。
 *
 * <p>{@code flatMap} 的并发度同时充当<b>在飞任务上限</b>：满了之后上游轮询自然被背压挡住，
 * 不需要额外的信号量。完整的准入限流与 429 降级是 P6。
 *
 * <p>P1 <b>不含</b>：{@code XAUTOCLAIM} 回收、{@code XCLAIM JUSTID} 心跳、死 consumer 清理、
 * Lua 原子摘牌。这些是 P3 的内容，缺了它们意味着<b>本阶段不具备崩溃接管能力</b> ——
 * 进程崩在处理中间时，那个令牌会一直留在 PEL 里没人回收。单节点开发期可以接受，
 * 上多节点之前必须补上（INV-2b）。
 */
public final class ReadyDispatcher implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ReadyDispatcher.class);

    /** 消费组名。所有 pod 共用一个组，令牌在组内被抢占式分配。 */
    public static final String GROUP = "workers";

    private static final int DEFAULT_CONCURRENCY = 8;
    private static final int READ_COUNT = 16;

    private final RedisRuntime runtime;
    private final SessionWorker worker;
    private final String consumerName;
    private final int concurrency;
    private final Duration pollInterval;

    private volatile Disposable subscription;

    public ReadyDispatcher(RedisRuntime runtime, SessionWorker worker, String consumerName) {
        this(runtime, worker, consumerName, DEFAULT_CONCURRENCY, Duration.ofMillis(50));
    }

    public ReadyDispatcher(RedisRuntime runtime, SessionWorker worker, String consumerName,
                           int concurrency, Duration pollInterval) {
        this.runtime = runtime;
        this.worker = worker;
        this.consumerName = consumerName;
        this.concurrency = concurrency;
        this.pollInterval = pollInterval;
    }

    /** 建组（幂等）后开始消费。返回后调度已在后台运行。 */
    public Mono<Void> start() {
        return ensureGroup().doOnSuccess(ignored -> {
            subscription = consumeLoop().subscribe(
                    handled -> {
                    },
                    error -> log.error("ready 消费循环异常终止", error));
            log.info("任务控制模块已启动：consumer={} 并发={}", consumerName, concurrency);
        });
    }

    /**
     * 建消费组。
     *
     * <p>{@code MKSTREAM} 让 ready 还不存在时也能建组 —— 否则冷启动的第一个 pod 会失败。
     * {@code BUSYGROUP} 表示别的 pod 已经建过了，那是正常情况，不是错误。
     */
    private Mono<Void> ensureGroup() {
        return runtime.commands()
                .xgroupCreate(XReadArgs.StreamOffset.from(KeyNamespace.READY, "0"), GROUP,
                        XGroupCreateArgs.Builder.mkstream())
                .then()
                .onErrorResume(error -> isGroupExists(error) ? Mono.empty() : Mono.error(error));
    }

    private static boolean isGroupExists(Throwable error) {
        return error.getMessage() != null && error.getMessage().contains("BUSYGROUP");
    }

    private Flux<Void> consumeLoop() {
        return Flux.defer(this::pollOnce)
                // 轮询与处理分离：repeatWhen 只作用在读取上，
                // 慢 turn 不会挡住下一次轮询（挡住它的应该是并发上限，不是时序）
                .repeatWhen(completed -> completed.delayElements(pollInterval))
                .flatMap(this::handleToken, concurrency);
    }

    private Flux<StreamMessage<String, String>> pollOnce() {
        return runtime.commands().xreadgroup(
                Consumer.from(GROUP, consumerName),
                XReadArgs.Builder.count(READ_COUNT),
                XReadArgs.StreamOffset.lastConsumed(KeyNamespace.READY));
    }

    /**
     * 处理一个令牌，然后交差。
     *
     * <p>顺序是 <b>抽干 → 摘牌 → XACK</b>（INV-4）。Worker 内部完成前两步，
     * 这里做第三步。交差提前的话，崩在中间就没有任何线索。
     */
    private Mono<Void> handleToken(StreamMessage<String, String> entry) {
        ReadyToken token;
        try {
            token = StreamPayload.read(entry.getBody(), ReadyToken.class);
        } catch (RuntimeException e) {
            log.error("ready 令牌无法解析，直接交差：id={}", entry.getId(), e);
            return ack(entry).then();
        }

        return worker.handle(token)
                .onErrorResume(error -> {
                    // 一个 session 出错不能让整个调度循环停下来
                    log.error("session {} 处理失败", token.sessionId(), error);
                    return Mono.empty();
                })
                .then(ack(entry))
                .then();
    }

    private Mono<Long> ack(StreamMessage<String, String> entry) {
        return runtime.commands().xack(KeyNamespace.READY, GROUP, entry.getId());
    }

    @Override
    public void close() {
        Disposable running = subscription;
        if (running != null) {
            running.dispose();
        }
    }
}

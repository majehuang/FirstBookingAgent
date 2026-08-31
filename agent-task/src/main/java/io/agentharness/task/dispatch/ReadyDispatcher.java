package io.agentharness.task.dispatch;

import io.agentharness.keys.KeyNamespace;
import io.agentharness.redis.ReadyToken;
import io.agentharness.redis.RedisRuntime;
import io.agentharness.redis.StreamPayload;
import io.agentharness.task.lease.ActiveTurns;
import io.agentharness.task.lease.LeaseControl;
import io.agentharness.task.health.HealthLog;
import io.agentharness.task.health.HealthProbe;
import io.agentharness.task.health.QueueHealth;
import io.agentharness.task.lease.TurnHandoff;
import io.agentharness.task.schedule.Periodic;
import io.agentharness.task.worker.SessionWorker;
import io.agentharness.task.worker.WorkOutcome;
import io.lettuce.core.Consumer;
import io.lettuce.core.StreamMessage;
import io.lettuce.core.XGroupCreateArgs;
import io.lettuce.core.XReadArgs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 任务控制模块：消费 ready，为每个令牌拉起一个 Worker。
 *
 * <p><b>这是全项目唯一允许使用 {@code flatMap} 的地方（INV-8）。</b>
 * 这里需要的正是它的并发语义：不同 session 必须能并行推进，
 * 一个 session 的慢 turn 不该挡住其它 session。事件管道则一律 {@code concatMap} ——
 * 那里需要的是顺序，用错就会消息错位。
 *
 * <h2>四件事同时在跑</h2>
 * <ol>
 *   <li><b>认领</b> —— {@code XREADGROUP}，条数 = 空闲槽位数（{@link InFlightSlots}）</li>
 *   <li><b>回收</b> —— {@code XAUTOCLAIM} 把死 pod 的令牌捞回来（{@link PendingReclaimer}）</li>
 *   <li><b>清理</b> —— 删掉确实死透的 consumer 元数据（{@link ConsumerJanitor}）</li>
 *   <li><b>处理</b> —— 抢 lease、抽干、摘牌、交差</li>
 * </ol>
 * 回收与清理由 {@link MaintenanceCycle} 按固定顺序串起来，理由见那个类。
 *
 * <h2>交差的判定不在这里</h2>
 * {@code XACK} 只在 {@link WorkOutcome#mayAck()} 为真时执行。
 * 这个决定必须由 Worker 给出 —— 只有它知道摘牌成功了没有。
 * 调度器"处理完就 ACK"是最自然的写法，也正是 INV-4 要防的那个写法。
 */
public final class ReadyDispatcher implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ReadyDispatcher.class);

    /** 消费组名。所有 pod 共用一个组，令牌在组内被抢占式分配。 */
    public static final String GROUP = "workers";

    private static final int DEFAULT_CONCURRENCY = 8;

    /**
     * 单次认领的条数上限。
     *
     * <p>空闲槽位很多时也不一次性全领走：一次 {@code XREADGROUP} 领 64 条，
     * 意味着这 64 条在被处理完之前，其它 pod 完全看不见它们。
     * 上限让"认领即持有"的窗口保持在可控范围（CAP-003）。
     */
    private static final int CLAIM_BATCH_CAP = 16;

    /** 停机时检查"在飞是否归零"的轮询粒度。 */
    private static final Duration DRAIN_POLL = Duration.ofMillis(100);

    /**
     * 交接留出的额外时间。
     *
     * <p>{@code close()} 的等待上限必须比宽限期长一点，否则宽限期刚满、交接才开始，
     * 阻塞等待就超时了 —— 那会把"主动交接"退化成"硬掐"，而且退化得毫无征兆。
     */
    private static final Duration HANDOFF_HEADROOM = Duration.ofSeconds(10);

    private final RedisRuntime runtime;
    private final SessionWorker worker;
    private final ConsumerName consumerName;
    private final TaskTimings timings;
    private final InFlightSlots slots;
    private final MaintenanceCycle maintenance;
    private final ActiveTurns activeTurns;
    private final TurnHandoff handoff;
    private final HealthProbe health;
    private final HealthLog healthLog;

    private final Disposable.Composite running = Disposables.composite();

    /**
     * 停机开关。
     *
     * <p>置位之后 {@link #pollOnce} 立刻返回空 —— <b>停止认领，但不打断在飞任务</b>。
     * 这个区分是优雅停机的全部难点：直接 dispose 订阅会把两件事一起做掉。
     */
    private final AtomicBoolean draining = new AtomicBoolean();

    /** 单独持有：停机时维护循环要先于消费循环停掉，见 {@link #shutdown()}。 */
    private volatile Disposable maintenanceSubscription;

    public ReadyDispatcher(RedisRuntime runtime, SessionWorker worker, ConsumerName consumerName,
                           int concurrency, LeaseControl leaseControl) {
        this(runtime, worker, consumerName, concurrency, leaseControl, HealthLog.disabled());
    }

    public ReadyDispatcher(RedisRuntime runtime, SessionWorker worker, ConsumerName consumerName,
                           int concurrency, LeaseControl leaseControl, HealthLog healthLog) {
        this.healthLog = healthLog;
        this.runtime = runtime;
        this.worker = worker;
        this.consumerName = consumerName;
        this.timings = leaseControl.timings();
        this.activeTurns = leaseControl.activeTurns();
        this.handoff = new TurnHandoff(runtime, leaseControl.leases(), GROUP);
        this.slots = new InFlightSlots(concurrency);
        this.health = new HealthProbe(runtime, GROUP, () -> new HealthProbe.LocalState(
                slots.inFlight(), slots.capacity(), activeTurns.longestHeld()));
        this.maintenance = new MaintenanceCycle(
                new PendingReclaimer(runtime, GROUP, consumerName, timings),
                new ConsumerJanitor(runtime, GROUP, consumerName, timings),
                slots, timings);
    }

    /**
     * 建组（幂等）→ 启动维护循环 → 开始消费。
     *
     * <p>维护循环<b>先于</b>消费循环启动，且它自带一次立即执行（RCV-001）：
     * 新 pod 起来的时刻往往正是别的 pod 刚死的时刻，PEL 里最可能有活儿等着。
     */
    public Mono<Void> start() {
        return ensureGroup().doOnSuccess(ignored -> {
            // 单独持有维护循环的订阅：停机时它要<b>先</b>被停掉。
            // 回收是另一条会把新工作拉进本 pod 的入口，只堵住 XREADGROUP 是不够的
            maintenanceSubscription = maintenance.schedule(this::handleToken).subscribe(
                    handled -> {
                    },
                    error -> {
                        // 必须同时走 stderr：slf4j-nop 之下 log.error 什么都不输出，
                        // 而这条循环一旦死掉，回收就永久停了 —— 正是 INV-2b 说的静默卡死
                        log.error("维护循环异常终止", error);
                        System.err.println("⚠ 维护循环异常终止 —— PEL 回收已停止，"
                                + "滞留令牌将不再被自动捞回（INV-2b）：" + error);
                    });
            running.add(maintenanceSubscription);

            running.add(consumeLoop().subscribe(
                    handled -> {
                    },
                    error -> log.error("ready 消费循环异常终止", error)));

            running.add(healthLoop().subscribe(
                    checked -> {
                    },
                    // 探针自己挂了要吵 —— 一个静默死掉的健康检查比没有健康检查更糟：
                    // 它会让人以为"没告警就是没问题"
                    error -> {
                        // 这一条必须同时走两条路：探针死了之后，"没有告警"会被读成
                        // "一切正常"，那正是最危险的误读
                        log.error("健康检查循环异常终止", error);
                        System.err.println("⚠ 队列健康检查已停止运行，"
                                + "此后 PEL 滞留与卡死 turn 将不再有任何告警："
                                + error);
                    }));

            log.info("任务控制模块已启动：consumer={} 并发={} MIN-IDLE={} 回收周期={}",
                    consumerName, slots.capacity(),
                    timings.reclaimMinIdle(), timings.reclaimInterval());
        });
    }

    /**
     * 周期健康检查：<b>健康时不出声，有问题时每轮都吵。</b>
     *
     * <p>不打"一切正常"的心跳日志：那种日志跑久了没人看，真出问题时反而淹在里面。
     * 代价是"探针还活着吗"没法从日志确认 —— 所以上面的错误处理必须响。
     *
     * <p>与维护循环同频（30s）。这个频率的意义是：{@code XAUTOCLAIM} 一旦停跑，
     * PEL 最老 idle 会持续增长，几个周期之内就会越过告警线并开始每 30 秒喊一次。
     */
    private Flux<Void> healthLoop() {
        return Periodic.ticks(timings.reclaimInterval(),
                        skipped -> log.debug("健康检查上一轮尚未跑完，跳过本拍 {}", skipped))
                .concatMap(tick -> health.probe())
                .doOnNext(this::reportHealth)
                .then()
                .flux();
    }

    private void reportHealth(QueueHealth snapshot) {
        // 走 HealthLog 而不是 slf4j：agent-cli 绑的是 slf4j-nop，
        // log.warn 在真正发布出去的那个二进制里什么都不会输出
        healthLog.report(snapshot, timings.maxLeaseHold());
        log.debug("队列健康：{}", snapshot.summary());
    }

    /** 诊断与测试用：立刻采一次健康快照。 */
    public Mono<QueueHealth> health() {
        return health.probe();
    }

    /**
     * 建消费组。
     *
     * <p>{@code MKSTREAM} 让 ready 还不存在时也能建组 —— 否则冷启动的第一个 pod 会失败。
     * {@code BUSYGROUP} 表示别的 pod 已经建过了，那是正常情况，不是错误。
     *
     * <p>起始位置是 {@code 0} 而不是 {@code $}：{@code $} 表示"只要此刻之后的新消息"，
     * 冷启动时它会把已经积压在 ready 里的令牌<b>全部跳过</b>，而那些正是等着被处理的。
     *
     * <p>只吞 {@code BUSYGROUP}，其它错误必须原样抛出（GRP-002）。
     * 一律吞掉是很常见的写法，代价是"Redis 权限不足"这类问题会伪装成启动成功，
     * 直到第一次 {@code XREADGROUP} 才报错 —— 那时候已经在线上了。
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
                .repeatWhen(completed -> completed.delayElements(timings.pollInterval()))
                .flatMap(this::handleToken, slots.capacity());
    }

    /**
     * 认领一批 —— <b>条数等于空闲槽位数</b>（CAP-002 / CAP-003）。
     *
     * <p>满载时返回空而不是照领不误：领回来的令牌会进本 pod 的 PEL，
     * 其它空闲 pod 从此看不见它们。多领制造的不是缓冲，是饥饿。
     */
    private Flux<StreamMessage<String, String>> pollOnce() {
        if (draining.get()) {
            return Flux.empty();
        }
        int budget = Math.min(slots.free(), CLAIM_BATCH_CAP);
        if (budget <= 0) {
            return Flux.empty();
        }
        return runtime.commands().xreadgroup(
                Consumer.from(GROUP, consumerName.value()),
                XReadArgs.Builder.count(budget),
                XReadArgs.StreamOffset.lastConsumed(KeyNamespace.READY));
    }

    /**
     * 处理一个令牌，然后<b>按结局</b>决定要不要交差。
     *
     * <p>顺序是 <b>抽干 → 摘牌 → XACK</b>（INV-4）。Worker 完成前两步并给出结局，
     * 这里做第三步。不能交差的令牌留在 PEL 里，等回收把它重新投递出去。
     */
    private Mono<Void> handleToken(StreamMessage<String, String> entry) {
        if (draining.get()) {
            // 停机已开始。令牌不交差，留在 PEL 里 —— 别的 pod 会通过回收拿到它。
            // 这一道是兜底：认领的两个入口（XREADGROUP / XAUTOCLAIM）都已经关了，
            // 但关闭与已在管道里的令牌之间仍有窗口
            log.debug("停机中，令牌 {} 不再开工，留在 PEL 等待接管", entry.getId());
            return Mono.empty();
        }
        if (!slots.tryAcquire()) {
            // 认领与处理之间槽位被别人占满了。不交差 —— 令牌留在 PEL，
            // 回收会把它送回来。这里丢弃它才是真的丢工作
            log.debug("槽位已满，令牌 {} 留在 PEL 等待下一轮", entry.getId());
            return Mono.empty();
        }

        return processToken(entry)
                // 所有终止路径都必须恰好还一次槽（CAP-005）。
                // doFinally 覆盖完成、异常、取消三种信号，缺一种就会永久漏一个槽位
                .doFinally(signal -> slots.release());
    }

    private Mono<Void> processToken(StreamMessage<String, String> entry) {
        ReadyToken token;
        try {
            token = StreamPayload.read(entry.getBody(), ReadyToken.class);
        } catch (RuntimeException e) {
            // 解不开的令牌重投一万次也还是解不开，留在 PEL 只会让回收反复捞它。
            // 这是唯一一处"没做事也交差"的地方
            log.error("ready 令牌无法解析，直接交差：id={}", entry.getId(), e);
            return ack(entry).then();
        }

        return worker.handle(token, entry.getId())
                .onErrorResume(error -> {
                    // 一个 session 出错不能让整个调度循环停下来。
                    // 出错时不交差：令牌留在 PEL，由回收决定它的去向
                    log.error("session {} 处理失败，令牌不交差，留给回收", token.sessionId(), error);
                    return Mono.empty();
                })
                .flatMap(outcome -> {
                    if (!outcome.mayAck()) {
                        log.debug("令牌 {} 结局为 {}，不交差，留在 PEL 等待回收",
                                entry.getId(), outcome);
                        return Mono.<Long>empty();
                    }
                    return ack(entry);
                })
                .then();
    }

    private Mono<Long> ack(StreamMessage<String, String> entry) {
        return runtime.commands().xack(KeyNamespace.READY, GROUP, entry.getId());
    }

    /** 诊断与测试用：当前在飞任务数。 */
    public int inFlight() {
        return slots.inFlight();
    }

    /**
     * 优雅停机（CHA-009）：<b>先停认领，再等在飞，最后交接剩下的</b>。
     *
     * <pre>
     * ① 停止认领     —— 不再 XREADGROUP，也不再回收（马上要走了，捞回来也没人跑）
     * ② 等在飞跑完   —— 最长 shutdownGrace，跑完的 turn 用户完全无感知
     * ③ 交接剩下的   —— 释放执行权 + 重投唤醒令牌，别的 pod 立刻接手
     * ④ 断开订阅
     * </pre>
     *
     * <p><b>为什么不能一上来就 dispose。</b>取消订阅会连带取消所有在飞的
     * {@code handle()}，那等于把正在生成回复的 turn 拦腰砍断。用户看到的是回复说到一半
     * 停住，然后等 90 秒才由另一个 pod 从头重来。停止认领与打断在飞是两件事，
     * 必须分开做 —— 这就是 {@link #draining} 这个开关存在的全部理由。
     *
     * <p><b>③ 才是这个方法的价值所在。</b>没有交接的话，停机与硬杀走同一条恢复路径：
     * 牌子等 TTL、令牌等 idle + 回收周期，合计 90 秒。而生产里的 "pod 死亡" 绝大多数
     * 是发布 —— 不交接等于给每次发布的每个在飞 session 都加 90 秒静默期。
     * 交接之后这个代价接近 0，90 秒的 SLA 也就只覆盖真正的非预期死亡
     * （这正是采纳方案 A 的前提，见 {@link TaskTimings}）。
     */
    public Mono<Void> shutdown() {
        if (!draining.compareAndSet(false, true)) {
            return Mono.empty();
        }
        // 维护循环立刻停掉。它是<b>第二个</b>把新工作拉进本 pod 的入口 ——
        // 只把 XREADGROUP 堵住的话，XAUTOCLAIM 还会在宽限期里继续捞回别人的滞留令牌，
        // 而我们马上就要走了，捞回来只会让那些令牌再等一轮回收
        Disposable maintenanceRunning = maintenanceSubscription;
        if (maintenanceRunning != null) {
            maintenanceRunning.dispose();
        }

        log.info("停机开始：停止认领新令牌，等待 {} 个在飞 turn（最长 {}）",
                slots.inFlight(), timings.shutdownGrace());

        return awaitDrain()
                .then(Mono.defer(this::handOffRemaining))
                .doFinally(signal -> running.dispose());
    }

    /** 轮询等在飞归零，超时即止。归零后立刻返回，不空等满宽限期。 */
    private Mono<Void> awaitDrain() {
        return Flux.interval(DRAIN_POLL, DRAIN_POLL)
                .takeUntil(tick -> slots.inFlight() == 0)
                .take(timings.shutdownGrace())
                .then();
    }

    private Mono<Void> handOffRemaining() {
        List<ActiveTurns.Handle> remaining = activeTurns.snapshot();
        if (remaining.isEmpty()) {
            log.info("停机：在飞 turn 已全部跑完，用户无感知");
            return Mono.empty();
        }
        log.warn("停机：宽限期结束仍有 {} 个 turn 在飞，主动交接给其他节点", remaining.size());
        // concatMap 而不是 flatMap：停机路径上并发地改 lease 与 ready 只会让日志错乱，
        // 而这里最多几十条，串行完全够快
        return Flux.fromIterable(remaining).concatMap(handoff::handOff).then();
    }

    /**
     * 关闭。
     *
     * <p>走完整的优雅停机再断开 —— {@code close()} 是 try-with-resources 与
     * {@code WorkerCommand} 的收尾入口，如果它绕过交接，那么优雅停机就只是一个
     * 没人调用的方法。
     */
    @Override
    public void close() {
        shutdown().block(timings.shutdownGrace().plus(HANDOFF_HEADROOM));
        running.dispose();
    }
}

package io.agentharness.task.worker;

import io.agentharness.engine.EventMapper;
import io.agentharness.engine.EventTrace;
import io.agentharness.engine.MessageDraft;
import io.agentharness.engine.TurnEngine;
import io.agentharness.keys.KeyNamespace;
import io.agentharness.protocol.ClientMessage;
import io.agentharness.protocol.ControlFrame;
import io.agentharness.protocol.InstructionKind;
import io.agentharness.protocol.MessageRole;
import io.agentharness.protocol.MessageType;
import io.agentharness.protocol.SessionRef;
import io.agentharness.protocol.TurnPhase;
import io.agentharness.protocol.UserInstruction;
import io.agentharness.redis.Cursors;
import io.agentharness.redis.LeaseGuard;
import io.agentharness.redis.ReadyToken;
import io.agentharness.redis.RedisRuntime;
import io.agentharness.redis.StreamPayload;
import io.agentharness.redis.UnleaseOutcome;
import io.agentharness.store.message.MessageRepository;
import io.agentharness.store.message.PendingMessage;
import io.agentharness.task.coldstore.ColdStorageBypass;
import io.agentharness.task.dispatch.TaskTimings;
import io.agentharness.task.lease.ActiveTurns;
import io.agentharness.task.lease.LeaseControl;
import io.agentharness.task.lease.LeaseFence;
import io.agentharness.task.lease.LeaseLostException;
import io.agentharness.task.schedule.Periodic;
import io.agentharness.task.outbox.OutboxStream;
import io.agentharness.task.outbox.OutboxWriter;
import io.agentharness.trace.TraceSink;
import io.agentharness.trace.TraceStage;
import io.lettuce.core.StreamMessage;
import io.lettuce.core.XReadArgs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 一个 session 的执行。
 *
 * <p>顺序是<b>抢牌 → 抽干 → 摘牌</b>，交差（{@code XACK}）由调度器在这之后做（INV-4）。
 * 先交差再摘牌的话，崩在中间就没有任何线索 —— 完整理由见 {@link WorkOutcome}。
 *
 * <p><b>抽干是循环而不是一次读取</b>：处理第一条的过程中新消息可能已经到了，
 * 读空一次就退出会把它们留成孤儿 —— 那些消息要等下一次唤醒才被处理，
 * 而唤醒可能永远不来（投递方以为已经唤醒过了）。
 *
 * <h2>持牌期间同时跑着三件事</h2>
 * <ul>
 *   <li><b>续租</b>（每 10s）—— 让 lease 不过期。失败即落闸，见下</li>
 *   <li><b>PEL 心跳</b>（同频）—— 让 ready 令牌的 idle 不增长，
 *       否则长 turn 会被 {@code XAUTOCLAIM} 误判为死亡并回收（INV-2c）</li>
 *   <li><b>抽干与推理</b> —— 正事</li>
 * </ul>
 * 前两件由同一个定时器驱动，<b>必须同频</b>：分开之后两者会漂移，
 * 而漂移的后果是某一拍只续了 lease 没刷 idle（或反过来），
 * 于是要么牌子在手上但令牌被抢走，要么令牌在手上但牌子没了。
 *
 * <h2>续租失败：立即中止，且不留痕迹</h2>
 * 续租失败意味着牌子已经不在手上，很可能已有另一个 pod 接管。此刻必须
 * <b>停止一切写入</b>（消息表、outbox、游标、ACK），否则会污染新持有者的输出（LSE-007/008/009）。
 * 这个"停"由 {@link LeaseFence} 落闸实现，不是靠异常传播 —— 一个 turn 里挂着好几条
 * 互不相连的流，异常传不过去。
 */
public final class SessionWorker {

    private static final Logger log = LoggerFactory.getLogger(SessionWorker.class);

    private static final int DRAIN_BATCH = 64;

    /**
     * 「抽干 → 摘牌返回仍有工作 → 再抽干」的最多轮数。
     *
     * <p>正常情况下一到两轮就收敛：摘牌说"仍有工作"意味着消息恰好落在抽干与摘牌之间，
     * 那是个很窄的窗口。持续撞上它只有一种解释 —— 投递速度长期高于处理速度。
     * 那时候<b>让出执行权比死守着更好</b>：令牌留在 PEL 里，回收会把它重新投递出去，
     * 别的 pod 可以接手。一直循环下去则会把这个 pod 的一个槽位永久占死。
     */
    private static final int MAX_DRAIN_ROUNDS = 32;

    private final RedisRuntime runtime;
    private final LeaseGuard leases;
    private final LeaseControl leaseControl;
    private final ActiveTurns activeTurns;
    private final TaskTimings timings;
    private final Cursors cursors;
    private final MessageRepository repository;
    private final TurnEngine engine;
    private final OutboxWriter outboxWriter;
    private final OutboxStream outbox;
    private final ControlPublisher control;
    private final ColdStorageBypass coldStorage;
    private final TraceSink trace;
    private final TurnLog turnLog;

    public SessionWorker(RedisRuntime runtime, LeaseControl leaseControl,
                         MessageRepository repository, TurnEngine engine,
                         OutboxWriter outboxWriter, OutboxStream outbox,
                         ControlPublisher control, ColdStorageBypass coldStorage) {
        this(runtime, leaseControl, repository, engine, outboxWriter, outbox, control,
                coldStorage, TraceSink.disabled(), TurnLog.disabled());
    }

    public SessionWorker(RedisRuntime runtime, LeaseControl leaseControl,
                         MessageRepository repository, TurnEngine engine,
                         OutboxWriter outboxWriter, OutboxStream outbox,
                         ControlPublisher control, ColdStorageBypass coldStorage,
                         TraceSink trace, TurnLog turnLog) {
        this.runtime = runtime;
        this.leaseControl = leaseControl;
        this.leases = leaseControl.leases();
        this.activeTurns = leaseControl.activeTurns();
        this.timings = leaseControl.timings();
        this.trace = trace;
        this.turnLog = turnLog;
        this.cursors = new Cursors(runtime);
        this.repository = repository;
        this.engine = engine;
        this.outboxWriter = outboxWriter;
        this.outbox = outbox;
        this.control = control;
        this.coldStorage = coldStorage;
    }

    /**
     * 处理一个唤醒令牌。
     *
     * @param tokenId ready 流里的条目 ID，用于 PEL 心跳；{@code null} 表示本次调用
     *                不经由消费组（单测与诊断路径），跳过心跳
     * @return 结局，调度器据此决定要不要交差
     */
    public Mono<WorkOutcome> handle(ReadyToken token, String tokenId) {
        SessionRef session = token.toSession();
        return leases.tryAcquire(session, timings.leaseTtl())
                .flatMap(held -> held
                        .map(lease -> {
                            trace.emit(TraceStage.READY_CLAIMED, session.sessionId(),
                                    () -> "执行权已抢到，租约 " + timings.leaseTtl().toSeconds() + "s");
                            return runUnderLease(session, lease, tokenId);
                        })
                        .orElseGet(() -> {
                            // 抢不到同样要打：一条消息迟迟没回复时，
                            // "被别人持着"和"根本没收到令牌"是两个完全不同的方向
                            trace.emit(TraceStage.READY_CLAIMED, session.sessionId(),
                                    () -> "执行权已被他人持有，跳过");
                            log.debug("session {} 已被他人持有，跳过", session.sessionId());
                            return Mono.just(WorkOutcome.HELD_BY_OTHER);
                        }));
    }

    /** 不经由消费组的入口：单测与诊断用，没有令牌因此没有心跳。 */
    public Mono<WorkOutcome> handle(ReadyToken token) {
        return handle(token, null);
    }

    private Mono<WorkOutcome> runUnderLease(SessionRef session, LeaseGuard.Held lease,
                                            String tokenId) {
        LeaseFence fence = new LeaseFence(session.sessionId());
        // 先登记再启动续租：持牌起点记在 handle 上，续租循环与健康探针读的是<b>同一个值</b>，
        // 各算各的迟早会对不上（登记进在飞表还为了停机交接，CHA-009）
        ActiveTurns.Handle handle = activeTurns.register(session, lease, tokenId, fence);
        Disposable keepAlive = startKeepAlive(handle, fence);

        return drainAndUnlease(session, lease, fence, 1)
                // 续租与心跳必须在 doFinally 取消（INV-4）。不取消的话 TTL 这张保护网就失效了：
                // 进程还活着但 turn 早已结束时，牌子会被一直续下去，session 永久死锁。
                // 注销也在这里 —— 漏掉的话停机会去交接一个早已结束的 turn
                .doFinally(signal -> {
                    keepAlive.dispose();
                    activeTurns.unregister(handle);
                });
    }

    /**
     * 抽干 → 摘牌，摘不掉就再抽一轮。
     *
     * <p>摘牌返回"仍有工作"时<b>不能交差也不能重试摘牌</b> —— 要回去继续 drain。
     * 重试摘牌是个很自然的写法，但那条新消息会被跳过：脚本第二次仍会看到它，
     * 于是要么死循环、要么在某次恰好读到空时把它摘掉留成孤儿。
     */
    private Mono<WorkOutcome> drainAndUnlease(SessionRef session, LeaseGuard.Held lease,
                                              LeaseFence fence, int round) {
        if (fence.isLost()) {
            return Mono.just(WorkOutcome.LEASE_LOST);
        }
        if (round > MAX_DRAIN_ROUNDS) {
            log.warn("session {} 连续 {} 轮抽干后 inbox 仍非空，让出执行权由回收重新分配",
                    session.sessionId(), MAX_DRAIN_ROUNDS);
            return Mono.just(WorkOutcome.WORK_PENDING);
        }

        return drainLoop(session, fence)
                .then(Mono.defer(() -> {
                    if (fence.isLost()) {
                        return Mono.just(WorkOutcome.LEASE_LOST);
                    }
                    return leases.unlease(lease).flatMap(outcome -> switch (outcome) {
                        case UNLEASED -> Mono.just(WorkOutcome.COMPLETED);
                        case WORK_PENDING -> {
                            log.debug("session {} 摘牌时 inbox 又有新条目，继续抽干（第 {} 轮）",
                                    session.sessionId(), round);
                            yield drainAndUnlease(session, lease, fence, round + 1);
                        }
                        case NOT_HOLDER -> {
                            // 牌子在执行期间过期并被别人抢走了 —— 刚才那段我们一直在裸奔
                            log.warn("session {} 摘牌时发现执行权已易主，不交差，令牌留给回收",
                                    session.sessionId());
                            yield Mono.just(WorkOutcome.NOT_HOLDER);
                        }
                    });
                }))
                .onErrorResume(LeaseLostException.class,
                        error -> Mono.just(WorkOutcome.LEASE_LOST));
    }

    /**
     * 续租 + PEL 心跳，同一个定时器。
     *
     * <p>顺序是<b>先续租、后心跳</b>：续租失败时牌子已经不是我们的了，
     * 这时候再去刷令牌的 idle 只会让接管方晚一步发现它。
     */
    private Disposable startKeepAlive(ActiveTurns.Handle handle, LeaseFence fence) {
        Duration interval = timings.renewInterval();
        LeaseGuard.Held lease = handle.lease();
        String tokenId = handle.tokenId();

        // Periodic 而不是裸 Flux.interval：续租慢一次就让整条循环报错终止的话，
        // 牌子会在下一个 TTL 到期，turn 被无谓地中止。丢一拍是可以接受的
        // （TTL 是续租周期的三倍），永久停跑不行
        return Periodic.ticks(interval,
                        skipped -> log.warn("session {} 的续租上一拍尚未完成，跳过本拍 {}",
                                lease.session().sessionId(), skipped))
                .concatMap(tick -> handle.heldFor().compareTo(timings.maxLeaseHold()) > 0
                        ? Mono.fromRunnable(() -> abandonWedgedTurn(handle, fence))
                        : renewOnce(lease, fence)
                        .flatMap(renewed -> renewed && tokenId != null
                                ? touchToken(tokenId, lease)
                                : Mono.empty()))
                .subscribe(ignored -> {
                }, error -> {
                    // 定时器本身挂了等于没有续租，必须落闸 —— 否则我们会带着一个
                    // 即将过期的牌子继续写，而 30 秒后另一个 pod 就会合法接管
                    log.error("session {} 的续租定时器异常终止", lease.session().sessionId(), error);
                    fence.trip("续租定时器异常终止：" + rootMessage(error));
                });
    }

    /**
     * 持牌超过上限 —— <b>停止续租并落闸，让牌子自然过期</b>。
     *
     * <p>这是整个系统里唯一一条针对"进程活着但这一轮卡死"的防线，
     * 也是 <b>session 永久失聪</b>的唯一解药。
     *
     * <p>没有它的话：卡死的 turn 让续租一拍一拍地续下去，牌子<b>永不过期</b>；
     * 此后用户发的每一条消息都会被某个 pod 读到、看到"有人正在处理"、然后交差走人。
     * <b>用户重发多少次都没用</b>，而所有监控都正常 —— lease 在续、令牌在流转、没有异常。
     *
     * <p>TTL 救不了这种情况，因为 TTL 的前提是"没人续租了"。
     * 回收也救不了，因为令牌确实在这个 pod 手上、心跳也在刷。
     * 只有持牌方自己知道"我已经拿着这个牌子多久了"。
     *
     * <p>停止续租之后最多一个 TTL，牌子消失，<b>用户的下一条消息就能被正常处理</b>。
     * 这一轮本身是救不回来的（它卡在哪儿我们并不知道），所以不写收尾消息 ——
     * 用户看到的是这次没有回复，重发即可。<b>这是刻意的取舍：
     * 目标是让会话恢复可用，不是让这一轮起死回生。</b>
     */
    private void abandonWedgedTurn(ActiveTurns.Handle handle, LeaseFence fence) {
        Duration held = handle.heldFor();
        LeaseGuard.Held lease = handle.lease();
        log.error("session {} 持牌已达 {}，超过上限 {} —— 判定为卡死。"
                        + "停止续租并中止本轮，牌子将在一个 TTL（{}）内过期，"
                        + "此后该会话可以正常接收新消息。请查这一轮卡在哪里",
                lease.session().sessionId(), held, timings.maxLeaseHold(), timings.leaseTtl());
        fence.trip("持牌超过上限 " + timings.maxLeaseHold() + "，判定为卡死");
    }

    private Mono<Boolean> renewOnce(LeaseGuard.Held lease, LeaseFence fence) {
        return leases.renew(lease, timings.leaseTtl())
                .doOnNext(renewed -> {
                    if (!renewed) {
                        fence.trip("续租被拒（牌子已过期或已被他人抢占）");
                    }
                })
                .onErrorResume(error -> {
                    // 续租结果不确定时按失败处理（CHA-006）。
                    // "可能续上了"和"没续上"在这里必须做同一个决定：
                    // 赌它续上了的代价是双写，赌它没续上的代价只是这一轮白跑
                    fence.trip("续租命令失败，结果不确定：" + rootMessage(error));
                    return Mono.just(false);
                });
    }

    private Mono<Boolean> touchToken(String tokenId, LeaseGuard.Held lease) {
        if (!leaseControl.hasHeartbeat()) {
            return Mono.empty();
        }
        return leaseControl.heartbeat().touch(tokenId)
                .doOnNext(refreshed -> {
                    if (!refreshed) {
                        // 令牌已不在 PEL：要么已被交差（turn 正在收尾），
                        // 要么已被别的 pod 回收走。前者无害，后者说明我们掉队了 ——
                        // 但真正的判定归续租，这里只留一条线索
                        log.debug("session {} 的 ready 令牌 {} 已不在 PEL 中",
                                lease.session().sessionId(), tokenId);
                    }
                })
                .onErrorResume(error -> {
                    // 心跳失败不落闸：它只影响"多久之后被回收"，不影响写入的合法性。
                    // 合法性由续租判定，那边失败才是致命的
                    log.warn("session {} 的 PEL 心跳失败", lease.session().sessionId(), error);
                    return Mono.just(false);
                });
    }

    /** 读空为止。读空之后不再读一次是错的 —— 见类注释。 */
    private Mono<Void> drainLoop(SessionRef session, LeaseFence fence) {
        return Mono.defer(() -> drainOnce(session, fence))
                .flatMap(processed -> processed > 0 && !fence.isLost()
                        ? drainLoop(session, fence)
                        : Mono.empty())
                .then();
    }

    private Mono<Long> drainOnce(SessionRef session, LeaseFence fence) {
        String inboxKey = KeyNamespace.inbox(session.sessionId());
        return cursors.read(session.sessionId(), Cursors.Kind.MSG)
                .flatMapMany(cursor -> fence.fence(runtime.commands().xread(
                        XReadArgs.Builder.count(DRAIN_BATCH),
                        XReadArgs.StreamOffset.from(inboxKey, cursor))))
                // 严格按 Stream ID 顺序处理，一条完了才下一条
                .concatMap(entry -> processEntry(session, entry, fence))
                .count();
    }

    private Mono<Void> processEntry(SessionRef session, StreamMessage<String, String> entry,
                                    LeaseFence fence) {
        UserInstruction instruction;
        try {
            instruction = StreamPayload.read(entry.getBody(), UserInstruction.class);
        } catch (RuntimeException e) {
            // 解不开的条目不能卡住整个 session：推进游标，把它跳过去。
            // 这是唯一一处"没处理成功也推进游标"的地方 —— 因为重试一万次也还是解不开，
            // 而不推进就是让这个 session 永久停在这一条上（CUR-004）
            log.error("inbox 条目无法解析，已跳过：session={} id={}", session.sessionId(), entry.getId(), e);
            return advanceCursor(session, entry.getId(), fence);
        }
        return runInstruction(session, instruction, fence)
                .then(advanceCursor(session, entry.getId(), fence));
    }

    /**
     * 推进游标 —— <b>只在确实处理完之后，且执行权仍在手上</b>（LSE-009 / CUR-004）。
     *
     * <p>失去执行权后推进游标是最坏的一种写入：它会让接管方跳过这条还没处理的指令，
     * 而消息表里也不会有对应的回复。用户看到的是"这句话被彻底忽略了"，
     * 且没有任何一层能发现 —— 游标看起来完全正常。
     */
    private Mono<Void> advanceCursor(SessionRef session, String entryId, LeaseFence fence) {
        return fence.check(cursors.advance(session.sessionId(), Cursors.Kind.MSG, entryId));
    }

    private Mono<Void> runInstruction(SessionRef session, UserInstruction instruction,
                                      LeaseFence fence) {
        if (instruction.kind() != InstructionKind.MESSAGE) {
            // 控制指令由 ctrl 游标消费，P5 交付
            return Mono.empty();
        }

        return fence.check(blocking(() -> repository.appendUserMessage(session, newReplyId(),
                        "u-" + instruction.instructionId(), instruction.text(),
                        instruction.instructionId())))
                .flatMap(outcome -> fence.check(blocking(() ->
                        repository.claimTurn(session, instruction.instructionId())))
                        .flatMap(claimed -> {
                            if (!claimed) {
                                // 客户端重试会在 inbox 里留下同一指令的第二条。
                                // 认领失败说明这一轮已经跑过了，跳过即可（INV-1 的兑现点）
                                log.debug("指令 {} 已被认领过，跳过", instruction.instructionId());
                                return Mono.<Void>empty();
                            }
                            return runTurn(session, outcome.message(), instruction, fence);
                        }));
    }

    private Mono<Void> runTurn(SessionRef session, ClientMessage userMessage,
                               UserInstruction instruction, LeaseFence fence) {
        String replyId = userMessage.replyId();
        TurnStats stats = TurnStats.started();
        trace.emit(TraceStage.TURN_START, session.sessionId(),
                () -> "replyId=" + replyId
                        + " instructionId=" + instruction.instructionId()
                        + " seq=" + userMessage.msgSeq()
                        // 带上引擎名：判断"到底接没接上模型"是看追踪时最常问的一句，
                        // 原本只能靠 blockId 的 scripted-N 前缀去认，太隐晦
                        + " engine=" + engine.engineName());

        return outbox.publish(session, userMessage)
                .then(control.publish(session,
                        ControlFrame.idle().withTurnStarted(replyId).withPhase(TurnPhase.THINKING)))
                .thenMany(streamTurn(session, replyId, instruction.text(), stats, fence))
                .then(Mono.defer(() -> fence.isLost()
                        ? Mono.<Void>empty()
                        : control.publish(session,
                                ControlFrame.idle().withTurnEnded(TurnPhase.DONE))))
                .then()
                // 顺序不能反：doOnSuccess 排在 onErrorResume 之前，
                // 失败的那一轮才不会同时打出"完成"和"失败"两行
                .doOnSuccess(ignored -> {
                    if (fence.isLost()) {
                        // 闸门落下时上游是被"取消"掉的，下游看到的是正常完成 ——
                        // 不能在这里打"已完成"，那一轮根本没跑完
                        turnLog.turnFinished(stats.failed(session, replyId, engine.engineName(),
                                "执行权丢失，已中止"));
                    } else {
                        turnLog.turnFinished(stats.done(session, replyId, engine.engineName()));
                    }
                })
                .onErrorResume(error -> failTurn(session, replyId, stats, fence, error));
    }

    private Flux<ClientMessage> streamTurn(SessionRef session, String replyId, String text,
                                           TurnStats stats, LeaseFence fence) {
        Flux<PendingMessage> drafts = engine.stream(session, text)
                .doOnNext(event -> stats.countEvent())
                // 追踪的是引擎吐出的原始事件，排在 EventMapper 之前 ——
                // 映射本身就是最容易出错的一段，用映射后的结果去追踪等于自证清白
                .doOnNext(event -> trace.emit(TraceStage.STEP_EVENT, session.sessionId(),
                        () -> replyId + "  " + EventTrace.describe(event)))
                // 冷存储是 fire-and-forget 的旁路：它慢、它挂，都不影响这条流
                .doOnNext(event -> coldStorage.record(session, replyId, event))
                // INV-8：事件管道一律 concatMap。换 flatMap 小批量看不出，批稍大就错位
                .concatMap(event -> Flux.fromIterable(EventMapper.map(event, engine.renderers())))
                .map(draft -> toPending(draft, replyId))
                // INV-7：AgentStateStore 是阻塞接口，整条流必须 offload
                .subscribeOn(Schedulers.boundedElastic());

        // 闸门装在引擎与写入之间：落闸后引擎立刻被取消（模型停止生成、不再烧配额），
        // 已经在 80ms 合批窗口里飞着的那一批则由 OutboxWriter 内部的 check 拦下
        return outboxWriter.write(session, fence.fence(drafts), fence)
                .doOnNext(message -> stats.countMessage());
    }

    /**
     * turn 失败：已落库的内容保留，错误消息先落库再进流，然后正常收尾。
     *
     * <p><b>执行权丢失不走这条路。</b>那时候很可能已有另一个 pod 在回复同一个 session，
     * 我们再写一条 ERROR 进去，用户会在正常回复中间看到一句"失败了"（LSE-008）。
     */
    private Mono<Void> failTurn(SessionRef session, String replyId, TurnStats stats,
                                LeaseFence fence, Throwable error) {
        if (fence.isLost() || error instanceof LeaseLostException) {
            log.warn("session {} 的 turn {} 因执行权丢失而中止，不写任何收尾内容",
                    session.sessionId(), replyId);
            turnLog.turnFinished(stats.failed(session, replyId, engine.engineName(),
                    "执行权丢失，已中止"));
            return Mono.error(new LeaseLostException(
                    "session " + session.sessionId() + " 的执行权在 turn 中丢失"));
        }

        String reason = rootMessage(error);
        log.warn("session {} 的 turn {} 失败：{}", session.sessionId(), replyId, reason);
        turnLog.turnFinished(stats.failed(session, replyId, engine.engineName(), reason));

        PendingMessage errorMessage = new PendingMessage(replyId, "err", MessageRole.SYSTEM,
                MessageType.ERROR, reason, Map.of(), Instant.now());

        return fence.check(blocking(() -> repository.append(session, List.of(errorMessage))))
                .flatMapMany(Flux::fromIterable)
                .concatMap(message -> outbox.publish(session, message))
                .then(control.publish(session, ControlFrame.idle().withTurnEnded(TurnPhase.FAILED)))
                .onErrorResume(secondary -> {
                    // 连错误都写不进去时不要再抛：抽干循环必须继续，
                    // 否则一个坏 turn 会让整个 session 卡住
                    log.error("写入 turn 失败消息时再次失败：session={}", session.sessionId(), secondary);
                    return Mono.empty();
                })
                .then();
    }

    /**
     * 一轮的计数。
     *
     * <p>用原子量而不是不可变累加：计数发生在 Reactor 的 doOnNext 回调里，
     * 而那些回调可能落在不同线程上（{@code streamTurn} 整条流 offload 到 boundedElastic）。
     */
    private static final class TurnStats {

        private final Instant startedAt;
        private final java.util.concurrent.atomic.AtomicLong events =
                new java.util.concurrent.atomic.AtomicLong();
        private final java.util.concurrent.atomic.AtomicLong messages =
                new java.util.concurrent.atomic.AtomicLong();

        private TurnStats(Instant startedAt) {
            this.startedAt = startedAt;
        }

        static TurnStats started() {
            return new TurnStats(Instant.now());
        }

        void countEvent() {
            events.incrementAndGet();
        }

        void countMessage() {
            messages.incrementAndGet();
        }

        TurnLog.TurnSummary done(SessionRef session, String replyId, String engineName) {
            Instant now = Instant.now();
            return TurnLog.TurnSummary.done(now, session.sessionId(), replyId, engineName,
                    events.get(), messages.get(), Duration.between(startedAt, now));
        }

        TurnLog.TurnSummary failed(SessionRef session, String replyId, String engineName,
                                   String reason) {
            Instant now = Instant.now();
            return TurnLog.TurnSummary.failed(now, session.sessionId(), replyId, engineName,
                    events.get(), messages.get(), Duration.between(startedAt, now), reason);
        }
    }

    private static PendingMessage toPending(MessageDraft draft, String replyId) {
        return new PendingMessage(replyId, draft.blockKey(), draft.role(), draft.type(),
                draft.text(), draft.payload(), Instant.now());
    }

    /** 阻塞的 JDBC 调用统一从这里进 boundedElastic（INV-7）。 */
    private static <T> Mono<T> blocking(java.util.concurrent.Callable<T> work) {
        return Mono.fromCallable(work).subscribeOn(Schedulers.boundedElastic());
    }

    private static String newReplyId() {
        return "r-" + UUID.randomUUID().toString().substring(0, 12);
    }

    private static String rootMessage(Throwable error) {
        Throwable cursor = error;
        while (cursor.getCause() != null && cursor.getCause() != cursor) {
            cursor = cursor.getCause();
        }
        return cursor.getMessage() == null ? cursor.getClass().getSimpleName() : cursor.getMessage();
    }
}

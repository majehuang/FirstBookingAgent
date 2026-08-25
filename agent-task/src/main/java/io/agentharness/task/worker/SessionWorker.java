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
import io.agentharness.store.message.MessageRepository;
import io.agentharness.store.message.PendingMessage;
import io.agentharness.task.coldstore.ColdStorageBypass;
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
import java.util.Map;
import java.util.UUID;

/**
 * 一个 session 的执行。
 *
 * <p>顺序是<b>抢牌 → 抽干 → 摘牌</b>，交差（XACK）由调度器在这之后做（INV-4）。
 * 先交差再摘牌的话，崩在中间就没有任何线索：令牌已经确认消费、执行权却还挂着，
 * 直到 TTL 到期前这个 session 谁也碰不了。
 *
 * <p><b>抽干是循环而不是一次读取</b>：处理第一条的过程中新消息可能已经到了，
 * 读空一次就退出会把它们留成孤儿 —— 那些消息要等下一次唤醒才被处理，
 * 而唤醒可能永远不来（投递方以为已经唤醒过了）。
 */
public final class SessionWorker {

    private static final Logger log = LoggerFactory.getLogger(SessionWorker.class);

    private static final Duration LEASE_TTL = Duration.ofSeconds(30);
    private static final Duration RENEW_INTERVAL = Duration.ofSeconds(10);
    private static final int DRAIN_BATCH = 64;

    private final RedisRuntime runtime;
    private final LeaseGuard leases;
    private final Cursors cursors;
    private final MessageRepository repository;
    private final TurnEngine engine;
    private final OutboxWriter outboxWriter;
    private final OutboxStream outbox;
    private final ControlPublisher control;
    private final ColdStorageBypass coldStorage;
    private final TraceSink trace;

    public SessionWorker(RedisRuntime runtime, MessageRepository repository, TurnEngine engine,
                         OutboxWriter outboxWriter, OutboxStream outbox,
                         ControlPublisher control, ColdStorageBypass coldStorage) {
        this(runtime, repository, engine, outboxWriter, outbox, control, coldStorage,
                TraceSink.disabled());
    }

    public SessionWorker(RedisRuntime runtime, MessageRepository repository, TurnEngine engine,
                         OutboxWriter outboxWriter, OutboxStream outbox,
                         ControlPublisher control, ColdStorageBypass coldStorage,
                         TraceSink trace) {
        this.trace = trace;
        this.runtime = runtime;
        this.leases = new LeaseGuard(runtime);
        this.cursors = new Cursors(runtime);
        this.repository = repository;
        this.engine = engine;
        this.outboxWriter = outboxWriter;
        this.outbox = outbox;
        this.control = control;
        this.coldStorage = coldStorage;
    }

    /** 处理一个唤醒令牌。抢不到执行权就直接返回 —— 别人正在跑，重复执行才是错的。 */
    public Mono<Void> handle(ReadyToken token) {
        SessionRef session = token.toSession();
        return leases.tryAcquire(session, LEASE_TTL)
                .flatMap(held -> held
                        .map(lease -> {
                            trace.emit(TraceStage.READY_CLAIMED, session.sessionId(),
                                    () -> "执行权已抢到，租约 " + LEASE_TTL.toSeconds() + "s");
                            return drainUnderLease(session, lease);
                        })
                        .orElseGet(() -> {
                            // 抢不到同样要打：一条消息迟迟没回复时，
                            // "被别人持着"和"根本没收到令牌"是两个完全不同的方向
                            trace.emit(TraceStage.READY_CLAIMED, session.sessionId(),
                                    () -> "执行权已被他人持有，跳过");
                            log.debug("session {} 已被他人持有，跳过", session.sessionId());
                            return Mono.empty();
                        }));
    }

    private Mono<Void> drainUnderLease(SessionRef session, LeaseGuard.Held lease) {
        Disposable renewal = startRenewal(lease);
        return drainLoop(session)
                // 续租任务必须在 doFinally 取消。不取消的话 TTL 这张保护网就失效了，
                // 进程崩溃后牌子会被一直续下去，session 永久死锁（INV-4）
                .doFinally(signal -> renewal.dispose())
                .then(leases.release(lease))
                .doOnNext(released -> {
                    if (!released) {
                        // 值不匹配说明牌子已经过期并被别人抢走了 —— 我们刚才可能在裸奔
                        log.warn("session {} 释放执行权时值不匹配，牌子可能已过期", session.sessionId());
                    }
                })
                .then();
    }

    /** 每 10s 续一次租。P3 会加上"续租失败立即中止 turn"。 */
    private Disposable startRenewal(LeaseGuard.Held lease) {
        return Flux.interval(RENEW_INTERVAL, RENEW_INTERVAL)
                .concatMap(tick -> leases.renew(lease, LEASE_TTL))
                .doOnNext(renewed -> {
                    if (!renewed) {
                        log.warn("session {} 续租失败，执行权可能已易主", lease.session().sessionId());
                    }
                })
                .subscribe();
    }

    /** 读空为止。读空之后不再读一次是错的 —— 见类注释。 */
    private Mono<Void> drainLoop(SessionRef session) {
        return Mono.defer(() -> drainOnce(session))
                .flatMap(processed -> processed > 0 ? drainLoop(session) : Mono.empty());
    }

    private Mono<Long> drainOnce(SessionRef session) {
        String inboxKey = KeyNamespace.inbox(session.sessionId());
        return cursors.read(session.sessionId(), Cursors.Kind.MSG)
                .flatMapMany(cursor -> runtime.commands().xread(
                        XReadArgs.Builder.count(DRAIN_BATCH),
                        XReadArgs.StreamOffset.from(inboxKey, cursor)))
                // 严格按 Stream ID 顺序处理，一条完了才下一条
                .concatMap(entry -> processEntry(session, entry))
                .count();
    }

    private Mono<Void> processEntry(SessionRef session, StreamMessage<String, String> entry) {
        UserInstruction instruction;
        try {
            instruction = StreamPayload.read(entry.getBody(), UserInstruction.class);
        } catch (RuntimeException e) {
            // 解不开的条目不能卡住整个 session：推进游标，把它跳过去
            log.error("inbox 条目无法解析，已跳过：session={} id={}", session.sessionId(), entry.getId(), e);
            return cursors.advance(session.sessionId(), Cursors.Kind.MSG, entry.getId());
        }
        return runInstruction(session, instruction)
                .then(cursors.advance(session.sessionId(), Cursors.Kind.MSG, entry.getId()));
    }

    private Mono<Void> runInstruction(SessionRef session, UserInstruction instruction) {
        if (instruction.kind() != InstructionKind.MESSAGE) {
            // 控制指令由 ctrl 游标消费，P5 交付
            return Mono.empty();
        }

        return blocking(() -> repository.appendUserMessage(session, newReplyId(),
                        "u-" + instruction.instructionId(), instruction.text(),
                        instruction.instructionId()))
                .flatMap(outcome -> blocking(() ->
                        repository.claimTurn(session, instruction.instructionId()))
                        .flatMap(claimed -> {
                            if (!claimed) {
                                // 客户端重试会在 inbox 里留下同一指令的第二条。
                                // 认领失败说明这一轮已经跑过了，跳过即可（INV-1 的兑现点）
                                log.debug("指令 {} 已被认领过，跳过", instruction.instructionId());
                                return Mono.<Void>empty();
                            }
                            return runTurn(session, outcome.message(), instruction);
                        }));
    }

    private Mono<Void> runTurn(SessionRef session, ClientMessage userMessage,
                               UserInstruction instruction) {
        String replyId = userMessage.replyId();
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
                .thenMany(streamTurn(session, replyId, instruction.text()))
                .then(control.publish(session,
                        ControlFrame.idle().withTurnEnded(TurnPhase.DONE)))
                .then()
                .onErrorResume(error -> failTurn(session, replyId, error));
    }

    private Flux<ClientMessage> streamTurn(SessionRef session, String replyId, String text) {
        Flux<PendingMessage> drafts = engine.stream(session, text)
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

        return outboxWriter.write(session, drafts);
    }

    /** turn 失败：已落库的内容保留，错误消息先落库再进流，然后正常收尾。 */
    private Mono<Void> failTurn(SessionRef session, String replyId, Throwable error) {
        String reason = rootMessage(error);
        log.warn("session {} 的 turn {} 失败：{}", session.sessionId(), replyId, reason);

        PendingMessage errorMessage = new PendingMessage(replyId, "err", MessageRole.SYSTEM,
                MessageType.ERROR, reason, Map.of(), Instant.now());

        return blocking(() -> repository.append(session, java.util.List.of(errorMessage)))
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

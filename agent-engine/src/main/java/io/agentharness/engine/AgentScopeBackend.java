package io.agentharness.engine;

import io.agentharness.protocol.Ack;
import io.agentharness.protocol.ClientMessage;
import io.agentharness.protocol.ControlFrame;
import io.agentharness.protocol.InstructionKind;
import io.agentharness.protocol.MessageRole;
import io.agentharness.protocol.MessageType;
import io.agentharness.protocol.SessionRef;
import io.agentharness.protocol.TurnPhase;
import io.agentharness.protocol.UserInstruction;
import io.agentharness.store.message.MessageRepository;
import io.agentharness.store.message.PendingMessage;
import io.agentharness.tui.port.AgentBackend;
import io.agentharness.tui.port.HistorySource;
import io.agentscope.core.agent.Event;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 把 AgentScope 的事件流接到本项目的消息协议上。
 *
 * <p>三条不变量在这个类里同时落地，改动之前请先看清楚：
 *
 * <ul>
 *   <li><b>INV-5 先落库、后推流。</b>{@link #persist} 里 {@code append} 一定在 {@code emit} 之前。
 *       反过来会让用户"看到了、刷新后消失"—— 而且只在落库失败那一刻才暴露。</li>
 *   <li><b>INV-8 事件管道一律 {@code concatMap}。</b>换成 flatMap 时小批量看不出问题，
 *       批稍大就会出现 delta 错位。</li>
 *   <li><b>INV-7 阻塞调用必须 offload。</b>AgentStateStore 是阻塞接口，
 *       消息落库也是阻塞 JDBC，两者都跑在 {@code boundedElastic} 上。
 *       漏掉的话事件循环线程会被占死，症状是全进程吞吐掉到个位数。</li>
 * </ul>
 */
public final class AgentScopeBackend implements AgentBackend, HistorySource {

    private final TurnEngine engine;
    private final MessageRepository repository;
    private final String label;
    private final Map<String, SessionChannel> channels = new ConcurrentHashMap<>();

    public AgentScopeBackend(TurnEngine engine, MessageRepository repository, String label) {
        this.engine = engine;
        this.repository = repository;
        this.label = label;
    }

    @Override
    public String name() {
        return label;
    }

    @Override
    public Flux<ClientMessage> messages(SessionRef session) {
        return channel(session).messages();
    }

    @Override
    public Flux<ControlFrame> control(SessionRef session) {
        return channel(session).control();
    }

    @Override
    public Optional<HistorySource> history() {
        return Optional.of(this);
    }

    @Override
    public List<ClientMessage> since(SessionRef session, long sinceSeq, int limit) {
        return repository.since(session, sinceSeq, limit);
    }

    @Override
    public Mono<Ack> send(SessionRef session, UserInstruction instruction) {
        return Mono.fromSupplier(() -> instruction.kind() == InstructionKind.CONTROL
                        ? cancel(session, instruction)
                        : startTurn(session, instruction))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public void close() {
        channels.values().forEach(SessionChannel::close);
        channels.clear();
        engine.close();
    }

    // ---------- turn 生命周期 ----------

    private Ack startTurn(SessionRef session, UserInstruction instruction) {
        SessionChannel channel = channel(session);
        String replyId = "r-" + UUID.randomUUID().toString().substring(0, 12);

        // ① 用户消息先落库再推流。客户端不本地回显，它看到自己的话是从流里回来的 ——
        //    这样一个会话里只有一套顺序来源，多端也能看到彼此发的消息。
        MessageRepository.UserMessageOutcome outcome = repository.appendUserMessage(
                session, replyId, "u-" + instruction.instructionId(),
                instruction.text(), instruction.instructionId());
        channel.emit(outcome.message());

        if (!outcome.inserted()) {
            // 命中幂等：客户端在超时/5xx 之后带同一个 instructionId 重试（INV-1）。
            //
            // 这个判断必须排在"上一轮是否还在跑"之前 ——
            // 客户端超时重试最可能发生的时刻，恰恰就是上一轮还在跑的时候。
            // 顺序反了的话，重试会收到"上一轮尚未结束"的错误而不是原来的回执，
            // 于是客户端要么继续重试、要么放弃，两条路都不对。
            return new Ack(instruction.instructionId(),
                    outcome.message().replyId(), outcome.message().msgSeq());
        }

        // ② 确认是新指令之后，才轮到"能不能起新一轮"的判断
        if (channel.turnRunning()) {
            throw new IllegalStateException("上一轮回复尚未结束");
        }

        channel.publishControl(channel.snapshot().withTurnStarted(replyId).withPhase(TurnPhase.THINKING));

        Disposable subscription = engine.stream(session, instruction.text())
                .concatMap(event -> persist(session, channel, replyId, event))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        ignored -> {
                        },
                        error -> failTurn(session, channel, replyId, error),
                        () -> finishTurn(channel, replyId));
        channel.setActiveTurn(subscription);

        return new Ack(instruction.instructionId(), replyId, repository.lastSeq(session));
    }

    /**
     * 一个事件的落地。
     *
     * <p>整段跑在 {@code boundedElastic} 上：里面有两次阻塞 JDBC（分配序号、批量落库）。
     */
    private Mono<Void> persist(SessionRef session, SessionChannel channel, String replyId, Event event) {
        List<MessageDraft> drafts = EventMapper.map(event, engine.renderers());
        if (drafts.isEmpty()) {
            return Mono.empty();
        }

        return Mono.<Void>fromRunnable(() -> {
                    // 序号由消息表在事务内分配 —— 引擎侧完全不碰它。
                    // 分开分配的话，写入失败会烧掉序号并在序列里留下永久空洞（INV-10）
                    List<ClientMessage> batch = repository.append(session, toPending(drafts, replyId));

                    batch.forEach(channel::emit);        // 先落库、后推流，顺序不可颠倒（INV-5）
                    channel.advancePhase(batch);
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    private static List<PendingMessage> toPending(List<MessageDraft> drafts, String replyId) {
        Instant now = Instant.now();
        List<PendingMessage> pending = new ArrayList<>(drafts.size());
        for (MessageDraft draft : drafts) {
            pending.add(new PendingMessage(replyId, draft.blockKey(), draft.role(),
                    draft.type(), draft.text(), draft.payload(), now));
        }
        return List.copyOf(pending);
    }

    private void finishTurn(SessionChannel channel, String replyId) {
        channel.clearActiveTurn();
        ControlFrame snapshot = channel.snapshot();
        if (replyId.equals(snapshot.activeReplyId())) {
            channel.publishControl(snapshot.withTurnEnded(TurnPhase.DONE));
        }
    }

    private void failTurn(SessionRef session, SessionChannel channel, String replyId, Throwable error) {
        channel.clearActiveTurn();
        String reason = rootMessage(error);
        try {
            List<ClientMessage> written = repository.append(session, List.of(new PendingMessage(
                    replyId, "err", MessageRole.SYSTEM, MessageType.ERROR, reason,
                    Map.of(), Instant.now())));
            written.forEach(channel::emit);
        } catch (RuntimeException persistFailure) {
            // 连错误都落不了库时，至少让用户在界面上看到 —— 但不要因此吞掉原始异常
            channel.emit(ClientMessage.error(Long.MAX_VALUE, replyId, reason, Instant.now()));
        }
        channel.publishControl(channel.snapshot().withTurnEnded(TurnPhase.FAILED));
    }

    private Ack cancel(SessionRef session, UserInstruction instruction) {
        SessionChannel channel = channel(session);
        ControlFrame snapshot = channel.snapshot();
        String targetReplyId = instruction.targetReplyId();

        // targetReplyId 作用域校验：目标 turn 已不存在就丢弃，绝不误杀新 turn
        if (!snapshot.turnActive() || !targetReplyId.equals(snapshot.activeReplyId())) {
            return new Ack(instruction.instructionId(), targetReplyId, repository.lastSeq(session));
        }

        channel.publishControl(snapshot.withStopping());

        // 注意：HarnessAgent 是单例，interrupt() 没有 session 参数。
        // CLI 下一个进程只跑一个 turn，够用；多 session 并发时需要确认上游是否支持按 session 打断（P5）
        engine.interrupt();

        Disposable running = channel.clearActiveTurn();
        if (running != null) {
            running.dispose();
        }

        List<ClientMessage> stopped = repository.append(session, List.of(new PendingMessage(
                targetReplyId, "sys", MessageRole.SYSTEM, MessageType.SYSTEM,
                "已停止，上面已生成的部分保留", Map.of(), Instant.now())));
        stopped.forEach(channel::emit);

        channel.publishControl(channel.snapshot().withTurnEnded(TurnPhase.DONE));
        return new Ack(instruction.instructionId(), targetReplyId, repository.lastSeq(session));
    }

    /**
     * 取（或建）某个 session 的出站通道。
     *
     * <p>刻意<b>不</b>把历史灌进重放窗口。历史由客户端经 {@link HistorySource} 显式拉取 ——
     * 这样"首次打开会话"与"空窗恢复"走的是同一条代码路径，正是开发规划 B 节要求的。
     * 灌进来反而会让首帧 seq 变成 1、被判成正常追加，把那条路径绕过去。
     */
    private SessionChannel channel(SessionRef session) {
        return channels.computeIfAbsent(session.sessionId(), key -> new SessionChannel());
    }

    private static String rootMessage(Throwable error) {
        Throwable cursor = error;
        while (cursor.getCause() != null && cursor.getCause() != cursor) {
            cursor = cursor.getCause();
        }
        return cursor.getMessage() == null ? cursor.getClass().getSimpleName() : cursor.getMessage();
    }
}
